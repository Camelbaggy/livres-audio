package io.github.camelbaggy.livresaudio.core.playback

import io.github.camelbaggy.livresaudio.core.data.db.ProgressEntity
import io.github.camelbaggy.livresaudio.core.data.repo.ProgressRepository
import io.github.camelbaggy.livresaudio.core.data.repo.SaveResult
import io.github.camelbaggy.livresaudio.core.model.ListeningStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

const val BOOK_ID = "book-1"

fun file(n: Int, durationMs: Long? = 600_000L, md5: String? = "md5-$n") =
    PlaybackFile(driveFileId = "f$n", md5 = md5, durationMs = durationMs, name = "Fichier $n")

/** Three files of 10 minutes each by default. */
fun book(files: List<PlaybackFile> = listOf(file(0), file(1), file(2)), chapters: List<BookChapter> = emptyList()) =
    PlaybackBook(BOOK_ID, "acc", "Titre", "Auteur", null, files, chapters)

fun progress(
    fileIndex: Int = 1,
    md5: String? = "md5-1",
    driveFileId: String = "f1",
    positionMs: Long = 120_000,
    bookPositionMs: Long = 720_000,
    status: ListeningStatus = ListeningStatus.IN_PROGRESS
) = ProgressEntity(
    bookId = BOOK_ID,
    driveFileId = driveFileId,
    fileMd5 = md5,
    fileIndex = fileIndex,
    positionMs = positionMs,
    bookPositionMs = bookPositionMs,
    status = status,
    speed = null,
    lastPlayedAt = 1,
    updatedAt = 1,
    dirty = false
)

/** In-memory repository with the same reset safeguard as the Room one; records every call. */
class FakeProgressRepository(
    initial: ProgressEntity? = null,
    private val writeDelayMs: Long = 0,
    private val failTimes: Int = 0
) : ProgressRepository {
    data class Call(
        val fileIndex: Int,
        val positionMs: Long,
        val bookPositionMs: Long,
        val status: ListeningStatus,
        val allowReset: Boolean,
        val result: SaveResult
    )

    val state = MutableStateFlow(initial)
    val calls = mutableListOf<Call>()
    var maxConcurrent = 0
    private var running = 0
    private var failures = 0

    override fun observe(bookId: String): Flow<ProgressEntity?> = state.map { it }

    override suspend fun get(bookId: String): ProgressEntity? = state.value

    override suspend fun savePosition(
        bookId: String,
        driveFileId: String,
        fileMd5: String?,
        fileIndex: Int,
        positionMs: Long,
        bookPositionMs: Long,
        status: ListeningStatus,
        nowMs: Long,
        allowReset: Boolean
    ): SaveResult {
        running++
        maxConcurrent = maxOf(maxConcurrent, running)
        try {
            if (writeDelayMs > 0) delay(writeDelayMs)
            if (failures < failTimes) {
                failures++
                error("disque plein")
            }
            val existing = state.value
            val isReset = (positionMs == 0L && bookPositionMs == 0L) || status == ListeningStatus.NOT_STARTED
            val nonZero = existing != null && (existing.positionMs > 0 || existing.bookPositionMs > 0)
            val result = if (isReset && nonZero && !allowReset) {
                SaveResult.RefusedReset
            } else {
                state.value = progress(fileIndex, fileMd5, driveFileId, positionMs, bookPositionMs, status)
                SaveResult.Saved
            }
            calls += Call(fileIndex, positionMs, bookPositionMs, status, allowReset, result)
            return result
        } finally {
            running--
        }
    }

    override suspend fun setSpeedOverride(bookId: String, speed: Float?, nowMs: Long) = Unit
}

/** A scriptable player: tests set [snap] and then fire the keeper's events. */
class FakePlayer(private val book: PlaybackBook) {
    var snap = PlayerSnapshot(null, 0, 0, 0, KeeperPlayerState.IDLE, false)

    /** The player holds this book's playlist, positioned at [index]/[positionMs]. */
    fun at(index: Int, positionMs: Long, state: KeeperPlayerState = KeeperPlayerState.READY, playing: Boolean = false) {
        snap = PlayerSnapshot(
            mediaId = BookPlaylist.fileMediaId(book.bookId, book.files[index].driveFileId),
            mediaItemIndex = index,
            itemCount = book.files.size,
            positionMs = positionMs,
            state = state,
            isPlaying = playing
        )
    }
}
