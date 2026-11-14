package io.github.camelbaggy.livresaudio.core.data.repo

import androidx.room.withTransaction
import io.github.camelbaggy.livresaudio.core.data.db.LibraryDatabase
import io.github.camelbaggy.livresaudio.core.data.db.ProgressEntity
import io.github.camelbaggy.livresaudio.core.model.ListeningStatus
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Outcome of [ProgressRepository.savePosition]. */
sealed interface SaveResult {
    data object Saved : SaveResult

    /** A reset (position 0 / NOT_STARTED over a non-zero position) was refused: `allowReset` was not set. */
    data object RefusedReset : SaveResult
}

/** Listening positions. The golden rule of the project lives here (CLAUDE.md §5.3). */
interface ProgressRepository {
    fun observe(bookId: String): Flow<ProgressEntity?>

    suspend fun get(bookId: String): ProgressEntity?

    /**
     * Saves the position in ONE transaction and marks it `dirty` (to sync). Safeguard: writing a zero position or
     * `NOT_STARTED` over a non-zero saved position is refused unless [allowReset] is true. The caller sets it only
     * for a deliberate reset (user "reset", seek to the very start); never for periodic or shutdown saves.
     * The per-book speed override already stored is preserved.
     */
    suspend fun savePosition(
        bookId: String,
        driveFileId: String,
        fileMd5: String?,
        fileIndex: Int,
        positionMs: Long,
        bookPositionMs: Long,
        status: ListeningStatus,
        nowMs: Long,
        allowReset: Boolean = false
    ): SaveResult

    /** Sets or clears (null) the per-book speed override; no-op if the book has no progress row yet. */
    suspend fun setSpeedOverride(bookId: String, speed: Float?, nowMs: Long)
}

class RoomProgressRepository @Inject constructor(
    private val db: LibraryDatabase,
    @DataIoDispatcher private val dispatcher: CoroutineDispatcher
) : ProgressRepository {
    private val dao get() = db.progressDao()

    override fun observe(bookId: String): Flow<ProgressEntity?> = dao.observe(bookId)

    override suspend fun get(bookId: String): ProgressEntity? = withContext(dispatcher) { dao.get(bookId) }

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
    ): SaveResult = withContext(dispatcher) {
        db.withTransaction {
            val existing = dao.get(bookId)
            val isReset = (positionMs == 0L && bookPositionMs == 0L) || status == ListeningStatus.NOT_STARTED
            val existingNonZero = existing != null && (existing.positionMs > 0 || existing.bookPositionMs > 0)
            if (isReset && existingNonZero && !allowReset) {
                SaveResult.RefusedReset
            } else {
                dao.upsert(
                    ProgressEntity(
                        bookId = bookId,
                        driveFileId = driveFileId,
                        fileMd5 = fileMd5,
                        fileIndex = fileIndex,
                        positionMs = positionMs,
                        bookPositionMs = bookPositionMs,
                        status = status,
                        speed = existing?.speed,
                        lastPlayedAt = nowMs,
                        updatedAt = nowMs,
                        dirty = true
                    )
                )
                SaveResult.Saved
            }
        }
    }

    override suspend fun setSpeedOverride(bookId: String, speed: Float?, nowMs: Long) = withContext(dispatcher) {
        db.withTransaction {
            val current = dao.get(bookId) ?: return@withTransaction
            dao.upsert(current.copy(speed = speed, updatedAt = nowMs, dirty = true))
        }
    }
}
