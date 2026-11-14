package io.github.camelbaggy.livresaudio.core.playback

import io.github.camelbaggy.livresaudio.core.data.db.BookWithFiles

/** One audio file of a book, as the player needs it (no Room type beyond the mapper below). */
data class PlaybackFile(
    val driveFileId: String,
    val md5: String?,
    /** Unknown until the file is probed (CLAUDE.md §5.2). */
    val durationMs: Long?,
    val name: String
)

/** A chapter marker, positioned inside one file. [endMs] null = up to the end of the file. */
data class BookChapter(
    val indexInBook: Int,
    val title: String?,
    val fileIndex: Int,
    val startMs: Long,
    val endMs: Long?
)

/** What the player needs to know about a book; built from the library, immutable. */
data class PlaybackBook(
    val bookId: String,
    val accountId: String,
    val title: String?,
    val author: String?,
    val coverUri: String?,
    val files: List<PlaybackFile>,
    /** Embedded chapters only (empty = one chapter per file, see [ChapterTimeline]). */
    val embeddedChapters: List<BookChapter>
)

/** Maps a library row; files are ordered by `sortIndex`, chapters attached to their file's position. */
fun BookWithFiles.toPlaybackBook(): PlaybackBook {
    val ordered = files.sortedBy { it.sortIndex }
    val indexByFileId = ordered.withIndex().associate { (index, file) -> file.id to index }
    val chapterList = chapters
        .filter { it.fileId in indexByFileId }
        .sortedWith(compareBy({ indexByFileId.getValue(it.fileId) }, { it.startMs }, { it.indexInBook }))
        .mapIndexed { position, chapter ->
            BookChapter(
                indexInBook = position,
                title = chapter.title,
                fileIndex = indexByFileId.getValue(chapter.fileId),
                startMs = chapter.startMs,
                endMs = chapter.endMs
            )
        }
    return PlaybackBook(
        bookId = book.bookId,
        accountId = book.accountId,
        title = book.title,
        author = book.author,
        coverUri = book.coverUri,
        files = ordered.map { PlaybackFile(it.driveFileId, it.md5, it.durationMs, it.name) },
        embeddedChapters = chapterList
    )
}
