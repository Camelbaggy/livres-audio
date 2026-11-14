package io.github.camelbaggy.livresaudio.core.data.repo

import androidx.room.withTransaction
import io.github.camelbaggy.livresaudio.core.data.db.AnchorKind
import io.github.camelbaggy.livresaudio.core.data.db.BookEntity
import io.github.camelbaggy.livresaudio.core.data.db.BookField
import io.github.camelbaggy.livresaudio.core.data.db.BookFileEntity
import io.github.camelbaggy.livresaudio.core.data.db.BookWithFiles
import io.github.camelbaggy.livresaudio.core.data.db.BookWithProgress
import io.github.camelbaggy.livresaudio.core.data.db.LibraryDatabase
import io.github.camelbaggy.livresaudio.core.data.identity.FieldMeta
import io.github.camelbaggy.livresaudio.core.data.identity.MergedField
import io.github.camelbaggy.livresaudio.core.data.identity.bookIdFor
import io.github.camelbaggy.livresaudio.core.data.identity.fingerprint
import io.github.camelbaggy.livresaudio.core.data.identity.mergeField
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.BookAnchor
import io.github.camelbaggy.livresaudio.core.model.BookOrigin
import io.github.camelbaggy.livresaudio.core.model.BookState
import io.github.camelbaggy.livresaudio.core.model.DiscoveredBook
import io.github.camelbaggy.livresaudio.core.model.DriveItem
import io.github.camelbaggy.livresaudio.core.model.FieldSource
import io.github.camelbaggy.livresaudio.core.model.MatchState
import io.github.camelbaggy.livresaudio.core.model.ParsedName
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Books, their files and chapters: what Drive walks feed and what the UI displays (CLAUDE.md §4.2). */
interface LibraryRepository {
    /** PRESENT books with their progress, unordered (sorting/filtering is the caller's job). */
    fun observeLibrary(): Flow<List<BookWithProgress>>

    fun observeBook(bookId: String): Flow<BookWithFiles?>

    suspend fun getBook(bookId: String): BookWithFiles?

    /**
     * Creates or updates the book for [discovered] (identity = [bookIdFor]) and its files, in one transaction.
     * `FOLDER` information is merged with the CLAUDE.md §4.3 rule: it never replaces a higher provenance.
     * Probe results (duration, tags, chapters) of unchanged files (same MD5) are kept. A MISSING book comes back
     * PRESENT. Returns the `bookId`.
     */
    suspend fun upsertDiscoveredBook(
        discovered: DiscoveredBook,
        accountId: AccountId,
        origin: BookOrigin,
        nowMs: Long
    ): String

    /**
     * After a COMPLETE walk of [rootId] (Drive folder id): PRESENT books of that root not in [seenBookIds] become
     * MISSING with `missingSince = nowMs`. Returns how many were flagged. Never deletes.
     */
    suspend fun markMissingNotSeen(rootId: String, seenBookIds: Set<String>, nowMs: Long): Int

    /** Deletes books MISSING since before [cutoffMs] (90 days, CLAUDE.md §5.2). Progress and bookmarks stay. */
    suspend fun purgeMissingBefore(cutoffMs: Long): Int
}

class RoomLibraryRepository @Inject constructor(
    private val db: LibraryDatabase,
    @DataIoDispatcher private val dispatcher: CoroutineDispatcher
) : LibraryRepository {
    private val books get() = db.bookDao()
    private val files get() = db.bookFileDao()
    private val chapters get() = db.chapterDao()

    override fun observeLibrary(): Flow<List<BookWithProgress>> = books.observePresentWithProgress()

    override fun observeBook(bookId: String): Flow<BookWithFiles?> = books.observeWithFiles(bookId).map { it?.sorted() }

    override suspend fun getBook(bookId: String): BookWithFiles? =
        withContext(dispatcher) { books.getWithFiles(bookId)?.sorted() }

    override suspend fun upsertDiscoveredBook(
        discovered: DiscoveredBook,
        accountId: AccountId,
        origin: BookOrigin,
        nowMs: Long
    ): String = withContext(dispatcher) {
        val bookId = bookIdFor(discovered.anchor)
        db.withTransaction {
            val existing = books.get(bookId)
            val mergedFiles = mergeFiles(bookId, discovered, files.getForBook(bookId))
            val base = existing ?: newBook(bookId, discovered.anchor, nowMs)
            val updated = base.copy(
                accountId = accountId.value,
                rootId = discovered.rootId,
                driveFolderId = discovered.anchor.folderId(),
                origin = origin,
                fingerprint = fingerprint(discovered.files.mapNotNull { it.md5 }),
                state = BookState.PRESENT,
                missingSince = null,
                totalBytes = discovered.files.sumOf { it.sizeBytes ?: 0L },
                totalDurationMs = mergedFiles.totalDurationMs()
            ).withParsedName(discovered.parsed, FieldMeta(FieldSource.FOLDER, nowMs))
                .withFolderCover(
                    discovered.coverCandidates.firstOrNull(),
                    accountId,
                    FieldMeta(FieldSource.FOLDER, nowMs)
                )
            books.upsert(updated)
            applyFiles(bookId, mergedFiles)
        }
        bookId
    }

    override suspend fun markMissingNotSeen(rootId: String, seenBookIds: Set<String>, nowMs: Long): Int =
        withContext(dispatcher) {
            db.withTransaction {
                val gone = books.getByRoot(rootId)
                    .filter { it.state == BookState.PRESENT && it.bookId !in seenBookIds }
                    .map { it.bookId }
                gone.chunked(SQL_CHUNK).forEach { books.setState(it, BookState.MISSING, nowMs) }
                gone.size
            }
        }

    override suspend fun purgeMissingBefore(cutoffMs: Long): Int = withContext(dispatcher) {
        db.withTransaction {
            val ids = books.idsMissingBefore(cutoffMs)
            ids.chunked(SQL_CHUNK).forEach { books.delete(it) }
            ids.size
        }
    }

    private fun newBook(bookId: String, anchor: BookAnchor, nowMs: Long) = BookEntity(
        bookId = bookId,
        accountId = "",
        rootId = "",
        driveFolderId = anchor.folderId(),
        anchorKind = if (anchor is BookAnchor.Folder) AnchorKind.FOLDER else AnchorKind.SINGLE_FILE,
        anchorDriveId = anchor.driveId,
        origin = BookOrigin.PERSONAL,
        fingerprint = "",
        state = BookState.PRESENT,
        missingSince = null,
        addedAt = nowMs,
        totalDurationMs = null,
        totalBytes = 0,
        title = null,
        author = null,
        narrator = null,
        seriesName = null,
        seriesIndex = null,
        description = null,
        year = null,
        coverUri = null,
        fieldSources = emptyMap(),
        matchState = MatchState.UNMATCHED,
        providerIds = emptyMap()
    )

    /** Keeps ids and probe results of unchanged files; a file whose MD5 changed is treated as new content. */
    private fun mergeFiles(
        bookId: String,
        discovered: DiscoveredBook,
        existing: List<BookFileEntity>
    ): List<BookFileEntity> {
        val byDriveId = existing.associateBy { it.driveFileId }
        return discovered.files.mapIndexed { index, item ->
            val old = byDriveId[item.id]
            val kept = old?.takeIf { it.md5 == item.md5 }
            BookFileEntity(
                id = old?.id ?: 0,
                bookId = bookId,
                driveFileId = item.id,
                name = item.name,
                mimeType = item.mimeType,
                sizeBytes = item.sizeBytes ?: 0L,
                md5 = item.md5,
                sortIndex = index,
                durationMs = kept?.durationMs,
                trackNumber = kept?.trackNumber,
                discNumber = kept?.discNumber,
                probed = kept?.probed == true
            )
        }
    }

    private suspend fun applyFiles(bookId: String, merged: List<BookFileEntity>) {
        val existing = files.getForBook(bookId).associateBy { it.driveFileId }
        val keep = merged.map { it.driveFileId }.toSet()
        val removed = existing.values.filter { it.driveFileId !in keep }.map { it.id }
        val changed = merged.filter { m -> existing[m.driveFileId]?.let { it.md5 != m.md5 } == true }.map { it.id }
        // Chapters hang on file ids: drop those of replaced files (cascade handles removed files).
        if (changed.isNotEmpty()) chapters.deleteForFiles(changed)
        if (removed.isNotEmpty()) files.delete(removed)
        files.update(merged.filter { it.id != 0L })
        files.insert(merged.filter { it.id == 0L })
    }

    private fun List<BookFileEntity>.totalDurationMs(): Long? =
        if (isNotEmpty() && all { it.durationMs != null }) sumOf { it.durationMs ?: 0L } else null

    private fun BookAnchor.folderId(): String = when (this) {
        is BookAnchor.Folder -> folderId
        is BookAnchor.SingleFile -> parentFolderId
    }

    companion object {
        /** Stays under SQLite's bound-variable limit. */
        private const val SQL_CHUNK = 500
    }
}

/** Applies the folder-name information to a book through the single merge rule (CLAUDE.md §4.3). */
internal fun BookEntity.withParsedName(parsed: ParsedName, meta: FieldMeta): BookEntity {
    val sources = fieldSources.toMutableMap()

    fun <T> merge(field: String, current: T?, incoming: T?): T? {
        val result = mergeField(MergedField(current, sources[field]), incoming, meta)
        result.meta?.let { sources[field] = it }
        return result.value
    }
    val title = merge(BookField.TITLE, title, parsed.title)
    val author = merge(BookField.AUTHOR, author, parsed.author)
    val narrator = merge(BookField.NARRATOR, narrator, parsed.narrator)
    val seriesName = merge(BookField.SERIES_NAME, seriesName, parsed.seriesName)
    val seriesIndex = merge(BookField.SERIES_INDEX, seriesIndex, parsed.seriesIndex)
    return copy(
        title = title,
        author = author,
        narrator = narrator,
        seriesName = seriesName,
        seriesIndex = seriesIndex,
        fieldSources = sources
    )
}

/** Scheme of the cover URI of a Drive image found next to the audio files (`drive-image://<accountId>/<fileId>`). */
const val DRIVE_IMAGE_SCHEME = "drive-image"

fun driveImageUri(accountId: AccountId, driveFileId: String): String =
    "$DRIVE_IMAGE_SCHEME://${accountId.value}/$driveFileId"

/**
 * Cover found in the book folder (`cover.jpg`…), with provenance FOLDER: merged by the single rule (CLAUDE.md §4.3),
 * so it never replaces an EMBEDDED or USER cover.
 */
internal fun BookEntity.withFolderCover(candidate: DriveItem?, accountId: AccountId, meta: FieldMeta): BookEntity {
    if (candidate == null) return this
    val result = mergeField(
        MergedField(coverUri, fieldSources[BookField.COVER_URI]),
        driveImageUri(accountId, candidate.id),
        meta
    )
    val newMeta = result.meta ?: return this
    return copy(coverUri = result.value, fieldSources = fieldSources + (BookField.COVER_URI to newMeta))
}

/** Room does not order `@Relation` lists: files by `sortIndex`, chapters by `indexInBook`. */
private fun BookWithFiles.sorted() =
    copy(files = files.sortedBy { it.sortIndex }, chapters = chapters.sortedBy { it.indexInBook })
