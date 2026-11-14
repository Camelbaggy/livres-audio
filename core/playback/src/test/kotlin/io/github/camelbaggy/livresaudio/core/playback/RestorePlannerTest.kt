package io.github.camelbaggy.livresaudio.core.playback

import io.github.camelbaggy.livresaudio.core.model.ListeningStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class RestorePlannerTest {
    private fun plan(
        progress: io.github.camelbaggy.livresaudio.core.data.db.ProgressEntity?,
        book: PlaybackBook = book()
    ) = RestorePlanner.plan(progress, book, ChapterTimeline(book.files, book.embeddedChapters))

    @Test
    fun `no saved progress starts at the beginning`() {
        assertEquals(RestoreTarget(0, 0), plan(null))
    }

    @Test
    fun `restores by md5 and file position`() {
        assertEquals(RestoreTarget(1, 120_000), plan(progress()))
    }

    @Test
    fun `finds the file by md5 even when the order changed`() {
        val reordered = book(listOf(file(1), file(0), file(2)))
        // Saved on md5-1 which is now at index 0.
        assertEquals(RestoreTarget(0, 120_000), plan(progress(fileIndex = 1), reordered))
    }

    @Test
    fun `falls back to the book position when the md5 is unknown`() {
        val p = progress(md5 = "gone", driveFileId = "gone", positionMs = 5, bookPositionMs = 1_300_000)
        assertEquals(RestoreTarget(2, 100_000), plan(p))
    }

    @Test
    fun `null md5 matches on the drive file id`() {
        val noMd5 = book(listOf(file(0, md5 = null), file(1, md5 = null), file(2, md5 = null)))
        val p = progress(md5 = null, driveFileId = "f2", fileIndex = 2, positionMs = 77_000, bookPositionMs = 0)
        assertEquals(RestoreTarget(2, 77_000), plan(p, noMd5))
    }

    @Test
    fun `duplicated md5 prefers the saved index`() {
        val dup = book(listOf(file(0, md5 = "same"), file(1, md5 = "same"), file(2)))
        assertEquals(RestoreTarget(1, 120_000), plan(progress(md5 = "same", fileIndex = 1), dup))
        assertEquals(RestoreTarget(0, 120_000), plan(progress(md5 = "same", fileIndex = 5), dup))
    }

    @Test
    fun `position beyond the known duration is clamped`() {
        assertEquals(RestoreTarget(1, 600_000), plan(progress(positionMs = 900_000)))
    }

    @Test
    fun `finished book restarts from the beginning`() {
        assertEquals(
            RestoreTarget(0, 0),
            plan(progress(status = ListeningStatus.FINISHED, fileIndex = 2, md5 = "md5-2"))
        )
    }

    @Test
    fun `book without files starts at zero`() {
        assertEquals(RestoreTarget(0, 0), plan(progress(), book(emptyList())))
    }
}
