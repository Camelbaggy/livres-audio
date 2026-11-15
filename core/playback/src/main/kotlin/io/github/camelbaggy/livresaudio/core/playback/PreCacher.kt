package io.github.camelbaggy.livresaudio.core.playback

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * How much audio to keep cached ahead of the position. Extension point of the lot 2 playback part: the project
 * lead will back it with a user setting (CLAUDE.md §5.4 "réglable"); [targetMs] <= 0 disables pre-caching.
 */
fun interface PreCachePolicy {
    fun targetMs(metered: Boolean): Long
}

/** Defaults of CLAUDE.md §5.4: 60 min on Wi-Fi, 30 min on mobile data. */
object DefaultPreCachePolicy : PreCachePolicy {
    const val UNMETERED_MS = 60 * 60 * 1000L
    const val METERED_MS = 30 * 60 * 1000L

    override fun targetMs(metered: Boolean): Long = if (metered) METERED_MS else UNMETERED_MS
}

/** Where the listening is: what the pre-cacher plans from. Read on the service's (main) thread. */
data class PreCacheInput(val book: PlaybackBook, val fileIndex: Int, val positionMs: Long)

/** Downloads a byte range of a Drive file into the stream cache. Throws [IOException] on failure. */
fun interface PreCacheSink {
    suspend fun cache(ref: DriveMediaRef, startByte: Long, lengthBytes: Long)
}

/**
 * Keeps the continuation of the listening in the disk cache (CLAUDE.md §5.4), on a coroutine of the service.
 *
 * - One download at a time (ranges are processed sequentially, in [chunkBytes] pieces) and low priority: before
 *   each piece it waits while the player itself is starved ([playerNeedsData], i.e. buffering).
 * - [trigger] (new book, file change, seek, resume, network back) cancels the pass in progress and plans again
 *   from the new position after [debounceMs] (which also merges bursts of triggers). A cancelled pass loses at
 *   most the piece being written: the next one skips whatever is already cached.
 * - While playing, a pass is repeated every [refreshMs] so the window follows the position.
 * - Nothing runs offline. An I/O failure ends the pass quietly; the next trigger or refresh tries again.
 *
 * The cache key and the `DataSpec` are the player's ([PlaybackDataSpecs]), so what is written here is what
 * the player reads.
 */
class PreCacher(
    private val scope: CoroutineScope,
    private val sink: PreCacheSink,
    private val network: NetworkStatus,
    private val input: () -> PreCacheInput?,
    private val playerNeedsData: () -> Boolean,
    private val isPlaying: () -> Boolean,
    private val policy: PreCachePolicy = DefaultPreCachePolicy,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val refreshMs: Long = REFRESH_MS,
    private val yieldPollMs: Long = YIELD_POLL_MS,
    private val chunkBytes: Long = CHUNK_BYTES
) {
    private val triggers = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    /** Passes that ran to the end of their plan, and passes cut short by an error (diagnostics, tests). */
    @Volatile
    var completedPasses = 0
        private set

    @Volatile
    var failedPasses = 0
        private set

    fun start() {
        if (job != null) return
        job = scope.launch {
            triggers.receiveAsFlow().collectLatest {
                delay(debounceMs)
                while (true) {
                    runPass()
                    if (!isPlaying()) break // paused: one pass is enough, a resume triggers the next
                    delay(refreshMs)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Asks for a new plan from the current position (cheap, coalesced, safe from any call site). */
    fun trigger() {
        triggers.trySend(Unit)
    }

    private suspend fun runPass() {
        val current = input() ?: return
        if (!network.isOnlineNow()) return
        val target = policy.targetMs(network.isMeteredNow())
        val ranges = PreCachePlanner.plan(current.book.files, current.fileIndex, current.positionMs, target)
        for (range in ranges) {
            val file = current.book.files[range.fileIndex]
            val ref = DriveMediaRef(current.book.accountId, file.driveFileId, file.md5)
            var offset = 0L
            while (offset < range.lengthBytes) {
                while (playerNeedsData()) delay(yieldPollMs)
                val length = minOf(chunkBytes, range.lengthBytes - offset)
                try {
                    sink.cache(ref, range.startByte + offset, length)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: IOException) {
                    failedPasses++
                    return
                } catch (failure: RuntimeException) {
                    failedPasses++
                    return
                }
                offset += length
            }
        }
        completedPasses++
    }

    companion object {
        const val DEBOUNCE_MS = 1_500L
        const val REFRESH_MS = 60_000L
        const val YIELD_POLL_MS = 1_000L
        const val CHUNK_BYTES = 8L * 1024 * 1024
    }
}
