package io.github.camelbaggy.livresaudio.core.playback

import androidx.media3.common.util.UnstableApi
import io.github.camelbaggy.livresaudio.core.data.repo.ProgressRepository
import io.github.camelbaggy.livresaudio.core.data.repo.SaveResult
import io.github.camelbaggy.livresaudio.core.model.ListeningStatus
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Player state, without the Media3 type (the keeper is plain Kotlin and tested with a fake player). */
enum class KeeperPlayerState { IDLE, BUFFERING, READY, ENDED }

/**
 * What the keeper reads from the player at the time of an event. [mediaId] is the id of the current item
 * (see [BookPlaylist.fileMediaId]); [itemCount] the size of the playlist.
 */
data class PlayerSnapshot(
    val mediaId: String?,
    val mediaItemIndex: Int,
    val itemCount: Int,
    val positionMs: Long,
    val state: KeeperPlayerState,
    val isPlaying: Boolean
)

enum class DiscontinuityKind {
    /** A deliberate move: user seek, chapter skip. */
    SEEK,

    /** Anything else: automatic change of file, playlist change, internal adjustment. */
    OTHER
}

/**
 * Saves the listening position of ONE loaded book. THE golden rule of the project (CLAUDE.md §5.3): never lose
 * the position.
 *
 * Threading: every `on...` method, [resetToStart], [flush] and [finish] are called on the player's thread (main);
 * [snapshot] reads the player and is only called there. The actual database writes run on [writeDispatcher], one
 * at a time, so a blocked main thread (see [flushBlocking]) can never starve a write.
 *
 * Safeguards:
 * 1. **Restoration gate.** Nothing is written until [isRestored]: the player must be `READY`, on the expected
 *    file of THIS book, at the restore target (within [RESTORE_TOLERANCE_MS]). A user seek before that point
 *    replaces the target (the user's intent wins) and the gate then waits for the player to be there. Until then
 *    the position in Room is untouched, so a player that failed to restore (wrong file, position 0, error)
 *    cannot overwrite a good position. If the gate never opens, nothing is saved: deliberate, the saved position
 *    stays valid and the next load tries again.
 * 2. **Identity check on every save.** The current item must be exactly the file the book expects at that index.
 * 3. **No reset unless asked.** Saves never use `allowReset`; the repository itself refuses a zero position over
 *    a non-zero one. Only [resetToStart] (explicit user action) passes `allowReset = true`.
 * 4. **Serialized writes, last wins.** Requests overwrite a single pending slot; a worker drains it under a
 *    mutex. The only exception is that a zero-position request never replaces a pending explicit reset.
 */
@UnstableApi
class PositionKeeper(
    private val book: PlaybackBook,
    private val timeline: ChapterTimeline,
    initialTarget: RestoreTarget,
    private val repository: ProgressRepository,
    /** Scope of the player's thread: runs the periodic tick. */
    private val scope: CoroutineScope,
    /** Where writes run (never the thread blocked by [flushBlocking]). */
    private val writeDispatcher: CoroutineDispatcher,
    private val snapshot: () -> PlayerSnapshot,
    private val clock: () -> Long,
    private val periodMs: Long = SAVE_PERIOD_MS
) {
    private data class SaveRequest(
        val file: PlaybackFile,
        val fileIndex: Int,
        val positionMs: Long,
        val bookPositionMs: Long,
        val status: ListeningStatus,
        val nowMs: Long,
        val allowReset: Boolean
    ) {
        val isZeroPosition get() = positionMs == 0L && bookPositionMs == 0L
    }

    private var target: RestoreTarget = initialTarget
    private var restored = false
    private var closed = false
    private var tickJob: Job? = null

    private val pending = AtomicReference<SaveRequest?>(null)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val writeMutex = Mutex()
    private val worker: Job = scope.launch(writeDispatcher) { wake.consumeEach { drain() } }

    /** True once the player is effectively at the restore target; before that, nothing is ever written. */
    val isRestored: Boolean get() = restored

    /** Writes refused by the repository's reset safeguard (observed by tests and diagnostics). */
    @Volatile
    var refusedResets: Int = 0
        private set

    /** Writes that threw (disk error...); the next event writes a fresh position anyway. */
    @Volatile
    var failedWrites: Int = 0
        private set

    // ---- Player events (player thread) ----

    fun onIsPlayingChanged(isPlaying: Boolean) {
        if (closed) return
        if (isPlaying) {
            startTicking()
        } else {
            tickJob?.cancel()
            tickJob = null
            saveIfRestored(snapshot()) // pause, audio focus loss, end of media, buffering...
        }
    }

    /** `playWhenReady` turned false: pause requested, possibly while buffering (then `isPlaying` did not change). */
    fun onPlayWhenReadyChanged(playWhenReady: Boolean) {
        if (closed || playWhenReady) return
        saveIfRestored(snapshot())
    }

    fun onPlaybackStateChanged() {
        if (closed) return
        val snap = snapshot()
        when (snap.state) {
            KeeperPlayerState.READY -> tryRaise(snap)
            KeeperPlayerState.ENDED -> saveIfRestored(snap)
            else -> Unit
        }
    }

    fun onPositionDiscontinuity(kind: DiscontinuityKind) {
        if (closed) return
        val snap = snapshot()
        if (kind == DiscontinuityKind.SEEK && !restored && isCurrentItemOfBook(snap)) {
            target = RestoreTarget(snap.mediaItemIndex, snap.positionMs)
        }
        saveIfRestored(snap)
    }

    fun onPlayerError() {
        if (closed) return
        saveIfRestored(snapshot())
    }

    /**
     * Explicit "reset to zero" requested by the user (the only path with `allowReset = true`). The caller must
     * also move the player to the start; the gate is re-armed at the start so that saves resume once it is there.
     */
    fun resetToStart() {
        if (closed || book.files.isEmpty()) return
        target = RestoreTarget(0, 0)
        restored = false
        enqueue(
            SaveRequest(
                file = book.files.first(),
                fileIndex = 0,
                positionMs = 0,
                bookPositionMs = 0,
                status = ListeningStatus.NOT_STARTED,
                nowMs = clock(),
                allowReset = true
            )
        )
    }

    // ---- End of life ----

    /** Writes the current position now (if restored) and waits for the write. The keeper stays active. */
    suspend fun flush() {
        if (closed) return
        saveIfRestored(snapshot())
        drain()
    }

    /** [flush], then the keeper stops reacting to events and releases its coroutines. */
    suspend fun finish() {
        if (closed) return
        try {
            saveIfRestored(snapshot())
            closed = true
            tickJob?.cancel()
            drain() // the final request was captured above: events after this point are ignored
        } finally {
            closed = true
            tickJob?.cancel()
            worker.cancel()
            wake.close()
        }
    }

    /**
     * Synchronous final write for `onTaskRemoved` / `onDestroy`, so the last seconds are not lost when the process
     * is about to die. Blocks the calling (main) thread for at most [timeoutMs]; writes run on [writeDispatcher],
     * so they can complete while main is blocked. This `runBlocking` is the project's one tolerated exception
     * besides the OkHttp interceptors (CLAUDE.md §13). With [close] the keeper is finished too.
     */
    fun flushBlocking(timeoutMs: Long = FINAL_FLUSH_TIMEOUT_MS, close: Boolean = false) {
        runBlocking {
            withTimeoutOrNull(timeoutMs) { if (close) finish() else flush() }
        }
    }

    // ---- Internals ----

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                delay(periodMs)
                if (!closed) saveIfRestored(snapshot())
            }
        }
    }

    private fun saveIfRestored(snap: PlayerSnapshot) {
        if (!restored && !tryRaise(snap)) return
        buildRequest(snap)?.let(::enqueue)
    }

    /** Opens the gate when the player is ready, on the expected file, at the target position. */
    private fun tryRaise(snap: PlayerSnapshot): Boolean {
        if (restored) return true
        if (snap.state != KeeperPlayerState.READY) return false
        if (!isCurrentItemOfBook(snap) || snap.mediaItemIndex != target.fileIndex) return false
        if (abs(snap.positionMs - target.positionMs) > restoreToleranceMs(target.positionMs)) return false
        restored = true
        return true
    }

    /** The player is on item `index` of THIS book, and the playlist is this book's. */
    private fun isCurrentItemOfBook(snap: PlayerSnapshot): Boolean {
        val index = snap.mediaItemIndex
        if (snap.itemCount != book.files.size || index !in book.files.indices) return false
        return snap.mediaId == BookPlaylist.fileMediaId(book.bookId, book.files[index].driveFileId)
    }

    private fun buildRequest(snap: PlayerSnapshot): SaveRequest? {
        if (!isCurrentItemOfBook(snap) || snap.positionMs < 0) return null
        val index = snap.mediaItemIndex
        val finished = snap.state == KeeperPlayerState.ENDED && index == book.files.lastIndex
        return SaveRequest(
            file = book.files[index],
            fileIndex = index,
            positionMs = snap.positionMs,
            bookPositionMs = timeline.bookPositionMs(index, snap.positionMs),
            status = if (finished) ListeningStatus.FINISHED else ListeningStatus.IN_PROGRESS,
            nowMs = clock(),
            allowReset = false
        )
    }

    private fun enqueue(request: SaveRequest) {
        // Last wins, except that a zero position must not erase an explicit reset still waiting to be written.
        pending.updateAndGet { old -> if (old?.allowReset == true && request.isZeroPosition) old else request }
        wake.trySend(Unit)
    }

    /** Writes the pending request, if any. Serialized: one write at a time, whoever calls. */
    private suspend fun drain() {
        writeMutex.withLock {
            val request = pending.getAndSet(null) ?: return
            write(request)
        }
    }

    private suspend fun write(request: SaveRequest) {
        try {
            val result = repository.savePosition(
                bookId = book.bookId,
                driveFileId = request.file.driveFileId,
                fileMd5 = request.file.md5,
                fileIndex = request.fileIndex,
                positionMs = request.positionMs,
                bookPositionMs = request.bookPositionMs,
                status = request.status,
                nowMs = request.nowMs,
                allowReset = request.allowReset
            )
            if (result is SaveResult.RefusedReset) refusedResets++
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failedWrites++
            // Keep the request unless a newer one arrived meanwhile.
            pending.compareAndSet(null, request)
        }
    }

    companion object {
        /** Periodic save while playing (CLAUDE.md §5.3). */
        const val SAVE_PERIOD_MS = 5_000L

        /** Minimum allowed gap between the restore target and the position the player reports once ready. */
        const val RESTORE_TOLERANCE_MS = 3_000L

        /**
         * Allowed gap: at least [RESTORE_TOLERANCE_MS], and 1 % of the target. Seeking deep into a long VBR MP3
         * without an index is approximate (proportional to the offset); a fixed 3 s would keep the gate shut for a
         * whole session, so nothing would be saved. A failed restore (position 0) stays far outside the tolerance.
         */
        fun restoreToleranceMs(targetMs: Long): Long = maxOf(RESTORE_TOLERANCE_MS, targetMs / 100)

        const val FINAL_FLUSH_TIMEOUT_MS = 2_000L
    }
}
