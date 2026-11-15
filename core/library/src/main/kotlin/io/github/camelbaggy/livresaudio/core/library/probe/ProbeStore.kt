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
    /** Fully probed (phase 2): duration, track/disc, chapters. */
    val probed: Boolean,
    /** Tags and cover read (phase 1). */
    val tagsProbed: Boolean
)

/**
 * A present book and its files (ordered by `sortIndex`). [lastPlayedAt] comes from `progress` (null = never
 * played): books being listened to are probed first.
 */
data class ProbeBookRow(
    val bookId: String,
    val accountId: String,
    val files: List<ProbeFileRow>,
    val lastPlayedAt: Long? = null
)

data class FileProbeOutcome(
    val fileId: Long,
    val driveFileId: String,
    /** MD5 the file had when it was probed: a file whose content changed meanwhile is left unprobed. */
    val md5: String?,
    val durationMs: Long?,
    val track: Int?,
    val disc: Int?,
    /** Only written when [complete]. */
    val chapters: List<EmbeddedChapter>,
    /** true = phase 2 (file fully probed, chapters replaced); false = phase 1 (tags and header duration only). */
    val complete: Boolean = true
)

/** What one file taught us, written in a single transaction (a file is never half applied). */
data class FileProbeCommit(
    val bookId: String,
    val file: FileProbeOutcome,
    /** Book information (provenance `EMBEDDED`), null when this file does not describe the book. */
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

    suspend fun commitFile(commit: FileProbeCommit)

    /** Book information only (no file), e.g. when the tag order changes which file is first. */
    suspend fun commitInfo(bookId: String, fields: EmbeddedFields?, coverUri: String?, nowMs: Long)

    /** Sets `sortIndex` = position in [orderedFileIds] (all the files of the book, else nothing happens). */
    suspend fun applyOrder(bookId: String, orderedFileIds: List<Long>)
}

class RoomProbeStore(private val db: LibraryDatabase, private val dispatcher: CoroutineDispatcher) : ProbeStore {
    override suspend fun loadBooks(): List<ProbeBookRow> = withContext(dispatcher) {
        db.bookDao().observePresentWithProgress().first()
            .filter { it.book.state == BookState.PRESENT }
            .map { (book, progress) ->
                ProbeBookRow(
                    bookId = book.bookId,
                    accountId = book.accountId,
                    lastPlayedAt = progress?.lastPlayedAt,
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
                            probed = it.probed,
                            tagsProbed = it.tagsProbed || it.probed
                        )
                    }
                )
            }
    }

    override suspend fun commitFile(commit: FileProbeCommit) = withContext(dispatcher) {
        db.withTransaction {
            val books = db.bookDao()
            val files = db.bookFileDao()
            val chapters = db.chapterDao()
            val book = books.get(commit.bookId) ?: return@withTransaction
            val outcome = commit.file
            val current = files.getForBook(commit.bookId)
            val file = current.firstOrNull { it.id == outcome.fileId } ?: return@withTransaction
            if (file.driveFileId != outcome.driveFileId || file.md5 != outcome.md5) return@withTransaction
            if (outcome.complete) {
                files.setProbed(file.id, outcome.durationMs, outcome.track, outcome.disc)
                chapters.deleteForFiles(listOf(file.id))
                val rows = outcome.chapters.map {
                    ChapterEntity(
                        bookId = commit.bookId,
                        fileId = file.id,
                        indexInBook = 0, // renumbered below
                        title = it.title,
                        startMs = it.startMs,
                        endMs = it.endMs
                    )
                }
                if (rows.isNotEmpty()) chapters.insert(rows)
                renumberChapters(commit.bookId, current.associate { it.id to it.sortIndex })
            } else {
                files.setTagsProbed(file.id, outcome.durationMs, outcome.track, outcome.disc)
            }
            val refreshed = files.getForBook(commit.bookId)
            val total = if (refreshed.isNotEmpty() && refreshed.all { it.durationMs != null }) {
                refreshed.sumOf { it.durationMs ?: 0L }
            } else {
                book.totalDurationMs
            }
            val info = commit.fields ?: EmbeddedFields().takeIf { commit.coverUri != null }
            val withInfo = info?.let { book.withEmbedded(it, commit.coverUri, commit.nowMs) } ?: book
            books.upsert(withInfo.copy(totalDurationMs = total))
        }
    }

    /** `indexInBook` = rank in the book: files by `sortIndex`, then by start time (files commit one by one). */
    private suspend fun renumberChapters(bookId: String, sortIndexOf: Map<Long, Int>) {
        val chapters = db.chapterDao()
        chapters.getForBook(bookId)
            .sortedWith(compareBy({ sortIndexOf[it.fileId] ?: Int.MAX_VALUE }, { it.startMs }))
            .forEachIndexed { index, chapter -> if (chapter.indexInBook != index) chapters.setIndex(chapter.id, index) }
    }

    override suspend fun commitInfo(bookId: String, fields: EmbeddedFields?, coverUri: String?, nowMs: Long) =
        withContext(dispatcher) {
            db.withTransaction {
                val book = db.bookDao().get(bookId) ?: return@withTransaction
                val info = fields ?: EmbeddedFields().takeIf { coverUri != null } ?: return@withTransaction
                db.bookDao().upsert(book.withEmbedded(info, coverUri, nowMs))
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
