package io.github.camelbaggy.livresaudio.core.playback

import io.github.camelbaggy.livresaudio.core.data.db.AnchorKind
import io.github.camelbaggy.livresaudio.core.data.db.BookEntity
import io.github.camelbaggy.livresaudio.core.data.db.BookFileEntity
import io.github.camelbaggy.livresaudio.core.data.db.BookWithFiles
import io.github.camelbaggy.livresaudio.core.data.db.ChapterEntity
import io.github.camelbaggy.livresaudio.core.model.BookOrigin
import io.github.camelbaggy.livresaudio.core.model.BookState
import io.github.camelbaggy.livresaudio.core.model.MatchState
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackBookMapperTest {
    private val entity = BookEntity(
        bookId = BOOK_ID, accountId = "acc", rootId = "r", driveFolderId = "d", anchorKind = AnchorKind.FOLDER,
        anchorDriveId = "d", origin = BookOrigin.SHARED, fingerprint = "", state = BookState.PRESENT,
        missingSince = null, addedAt = 0, totalDurationMs = null, totalBytes = 0, title = "T", author = "A",
        narrator = null, seriesName = null, seriesIndex = null, description = null, year = null, coverUri = null,
        fieldSources = emptyMap(), matchState = MatchState.UNMATCHED, providerIds = emptyMap()
    )

    private fun fileEntity(id: Long, sortIndex: Int) = BookFileEntity(
        id = id, bookId = BOOK_ID, driveFileId = "f$id", name = "n$id", mimeType = "audio/mpeg", sizeBytes = 1,
        md5 = "m$id", sortIndex = sortIndex, durationMs = 1000, trackNumber = null, discNumber = null, probed = true
    )

    @Test
    fun `files follow sortIndex and chapters are attached to the file position`() {
        // Row ids deliberately not in playing order.
        val row = BookWithFiles(
            entity,
            files = listOf(fileEntity(10, 1), fileEntity(20, 0)),
            chapters = listOf(
                ChapterEntity(1, BOOK_ID, fileId = 10, indexInBook = 1, title = "second", startMs = 500, endMs = null),
                ChapterEntity(2, BOOK_ID, fileId = 20, indexInBook = 0, title = "first", startMs = 0, endMs = 500),
                ChapterEntity(3, BOOK_ID, fileId = 99, indexInBook = 2, title = "orphan", startMs = 0, endMs = null)
            )
        )
        val book = row.toPlaybackBook()
        assertEquals(listOf("f20", "f10"), book.files.map { it.driveFileId })
        assertEquals(listOf("first" to 0, "second" to 1), book.embeddedChapters.map { it.title to it.fileIndex })
        assertEquals(listOf(0, 1), book.embeddedChapters.map { it.indexInBook })
    }
}
