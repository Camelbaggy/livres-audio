package io.github.camelbaggy.livresaudio.core.library.probe

import androidx.room.withTransaction
import io.github.camelbaggy.livresaudio.core.data.db.ChapterEntity
import io.github.camelbaggy.livresaudio.core.data.db.LibraryDatabase
import io.github.camelbaggy.livresaudio.core.model.BookState
import io.github.camelbaggy.livresaudio.core.model.EmbeddedChapter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class ProbeFileRow(
    val id: Long,
    val driveFileId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val md5: String?,
    val sortIndex: Int,
    val durationMs: Long?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val probed: Boolean
)

/** A present book and its files (ordered by `sortIndex`). */
data class ProbeBookRow(val bookId: String, val accountId: String, val files: List<ProbeFileRow>)

data class FileProbeOutcome(
    val fileId: Long,
    val driveFileId: String,
    /** MD5 the file had when it was probed: a file whose content changed meanwhile is left unprobed. */
    val md5: String?,
    val durationMs: Long?,
    val track: Int?,
    val disc: Int?,
    val chapters: List<EmbeddedChapter>
)

/** Everything learned about one book in one run, written in a single transaction. */
data class BookProbeCommit(
    val bookId: String,
    val files: List<FileProbeOutcome>,
    val fields: EmbeddedFields?,
    val coverUri: String?,
    val nowMs: Long
)

/**
 * Storage seen by the probe. Local to `core:library` on purpose: it only uses the DAOs of `core:data`
 * (see the report for the `LibraryRepository` methods that would replace it).
 */
interface ProbeStore {
    suspend fun loadBooks(): List<ProbeBookRow>

    suspend fun commit(commit: BookProbeCommit)

    /** Sets `sortIndex` = position in [orderedFileIds] (all the files of the book, else nothing happens). */
    suspend fun applyOrder(bookId: String, orderedFileIds: List<Long>)
}

class RoomProbeStore(private val db: LibraryDatabase, private val dispatcher: CoroutineDispatcher) : ProbeStore {
    override suspend fun loadBooks(): List<ProbeBookRow> = withContext(dispatcher) {
        db.bookDao().observePresentWithProgress().first().map { it.book }
            .filter { it.state == BookState.PRESENT }
            .map { book ->
                ProbeBookRow(
                    bookId = book.bookId,
                    accountId = book.accountId,
                    files = db.bookFileDao().getForBook(book.bookId).map {
                        ProbeFileRow(
                            id = it.id,
                            driveFileId = it.driveFileId,
                            name = it.name,
                            mimeType = it.mimeType,
                            sizeBytes = it.sizeBytes,
                            md5 = it.md5,
                            sortIndex = it.sortIndex,
                            durationMs = it.durationMs,
                            trackNumber = it.trackNumber,
                            discNumber = it.discNumber,
                            probed = it.probed
                        )
                    }
                )
            }
    }

    override suspend fun commit(commit: BookProbeCommit) = withContext(dispatcher) {
        db.withTransaction {
            val books = db.bookDao()
            val files = db.bookFileDao()
            val chapters = db.chapterDao()
            val book = books.get(commit.bookId) ?: return@withTransaction
            val current = files.getForBook(commit.bookId).associateBy { it.id }
            val applied = commit.files.filter { o ->
                current[o.fileId]?.let { it.driveFileId == o.driveFileId && it.md5 == o.md5 } == true
            }
            if (applied.isEmpty()) return@withTransaction
            var chapterIndex = 0
            for (o in applied.sortedBy { current.getValue(it.fileId).sortIndex }) {
                files.setProbed(o.fileId, o.durationMs, o.track, o.disc)
                chapters.deleteForFiles(listOf(o.fileId))
                val rows = o.chapters.map {
                    ChapterEntity(
                        bookId = commit.bookId,
                        fileId = o.fileId,
                        indexInBook = chapterIndex++,
                        title = it.title,
                        startMs = it.startMs,
                        endMs = it.endMs
                    )
                }
                if (rows.isNotEmpty()) chapters.insert(rows)
            }
            val refreshed = files.getForBook(commit.bookId)
            val total = if (refreshed.isNotEmpty() && refreshed.all { it.durationMs != null }) {
                refreshed.sumOf { it.durationMs ?: 0L }
            } else {
                book.totalDurationMs
            }
            val withInfo = commit.fields?.let { book.withEmbedded(it, commit.coverUri, commit.nowMs) } ?: book
            books.upsert(withInfo.copy(totalDurationMs = total))
        }
    }

    override suspend fun applyOrder(bookId: String, orderedFileIds: List<Long>) = withContext(dispatcher) {
        db.withTransaction {
            val files = db.bookFileDao()
            val current = files.getForBook(bookId)
            if (current.map { it.id }.toSet() != orderedFileIds.toSet()) return@withTransaction
            val position = orderedFileIds.withIndex().associate { (index, id) -> id to index }
            files.update(current.map { it.copy(sortIndex = position.getValue(it.id)) })
        }
    }
}
