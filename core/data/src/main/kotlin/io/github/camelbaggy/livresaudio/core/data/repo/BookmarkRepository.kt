package io.github.camelbaggy.livresaudio.core.data.repo

import androidx.room.withTransaction
import io.github.camelbaggy.livresaudio.core.data.db.BookmarkEntity
import io.github.camelbaggy.livresaudio.core.data.db.LibraryDatabase
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Bookmarks. Deletion is a tombstone (`deleted = true`) so that it can be synced (CLAUDE.md D11). */
interface BookmarkRepository {
    /** Live bookmarks of a book, ordered by position in the book. */
    fun observe(bookId: String): Flow<List<BookmarkEntity>>

    /** Returns the new bookmark id. */
    suspend fun add(
        bookId: String,
        fileMd5: String?,
        positionMs: Long,
        bookPositionMs: Long,
        note: String?,
        nowMs: Long,
        id: String = UUID.randomUUID().toString()
    ): String

    suspend fun updateNote(id: String, note: String?, nowMs: Long)

    /** Tombstones the bookmark. */
    suspend fun delete(id: String, nowMs: Long)
}

class RoomBookmarkRepository @Inject constructor(
    private val db: LibraryDatabase,
    @DataIoDispatcher private val dispatcher: CoroutineDispatcher
) : BookmarkRepository {
    private val dao get() = db.bookmarkDao()

    override fun observe(bookId: String): Flow<List<BookmarkEntity>> = dao.observeForBook(bookId)

    override suspend fun add(
        bookId: String,
        fileMd5: String?,
        positionMs: Long,
        bookPositionMs: Long,
        note: String?,
        nowMs: Long,
        id: String
    ): String = withContext(dispatcher) {
        dao.upsert(
            BookmarkEntity(
                id = id,
                bookId = bookId,
                fileMd5 = fileMd5,
                positionMs = positionMs,
                bookPositionMs = bookPositionMs,
                note = note,
                createdAt = nowMs,
                updatedAt = nowMs,
                deleted = false,
                dirty = true
            )
        )
        id
    }

    override suspend fun updateNote(id: String, note: String?, nowMs: Long) = withContext(dispatcher) {
        db.withTransaction {
            val current = dao.get(id) ?: return@withTransaction
            dao.upsert(current.copy(note = note, updatedAt = nowMs, dirty = true))
        }
    }

    override suspend fun delete(id: String, nowMs: Long) = withContext(dispatcher) {
        db.withTransaction {
            val current = dao.get(id) ?: return@withTransaction
            dao.upsert(current.copy(deleted = true, updatedAt = nowMs, dirty = true))
        }
    }
}
