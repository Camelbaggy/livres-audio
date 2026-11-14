package io.github.camelbaggy.livresaudio.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChapterTimelineTest {
    private val perFile = ChapterTimeline(book().files, emptyList())

    // File 1 has three embedded chapters (0, 200 s, 400 s); files 0 and 2 have none.
    private val embedded = ChapterTimeline(
        book().files,
        listOf(
            BookChapter(0, "A", 1, 0, 200_000),
            BookChapter(1, "B", 1, 200_000, null),
            BookChapter(2, "C", 1, 400_000, null)
        )
    )

    @Test
    fun `without embedded chapters there is one chapter per file`() {
        assertEquals(3, perFile.chapters.size)
        assertEquals(listOf(0, 1, 2), perFile.chapters.map { it.fileIndex })
        assertEquals(600_000L, perFile.chapters[0].durationMs)
    }

    @Test
    fun `embedded chapters replace the per-file chapter of their file only`() {
        assertEquals(listOf(0, 1, 1, 1, 2), embedded.chapters.map { it.fileIndex })
        assertEquals(listOf(0, 1, 2, 3, 4), embedded.chapters.map { it.indexInBook })
        // End of an embedded chapter without end = start of the next, last one = end of file.
        assertEquals(listOf(200_000L, 400_000L, 600_000L), embedded.chapters.slice(1..3).map { it.endMs })
    }

    @Test
    fun `chapterAt picks the last chapter starting at or before the position`() {
        val table = listOf(
            Triple(1, 0L, 1),
            Triple(1, 199_999L, 1),
            Triple(1, 200_000L, 2),
            Triple(1, 599_000L, 3),
            Triple(0, 5_000L, 0),
            Triple(2, 0L, 4)
        )
        for ((file, pos, expected) in table) {
            assertEquals(expected, embedded.chapterAt(file, pos)?.indexInBook, "file=$file pos=$pos")
        }
    }

    @Test
    fun `next goes to the following chapter across files and stops at the last`() {
        assertEquals(FilePosition(1, 200_000), embedded.next(1, 0))
        assertEquals(FilePosition(1, 400_000), embedded.next(1, 250_000))
        assertEquals(FilePosition(2, 0), embedded.next(1, 450_000))
        assertEquals(FilePosition(1, 0), embedded.next(0, 10_000))
        assertNull(embedded.next(2, 10_000))
        assertEquals(FilePosition(1, 0), perFile.next(0, 0))
        assertNull(perFile.next(2, 0))
    }

    @Test
    fun `previous returns to the chapter start after 3 s, else to the chapter before`() {
        val table = listOf(
            // (file, position) -> expected
            Triple(1, 250_000L, FilePosition(1, 200_000)), // 50 s in: start of own chapter
            Triple(1, 203_001L, FilePosition(1, 200_000)), // just over 3 s: own start
            Triple(1, 203_000L, FilePosition(1, 0)), // exactly 3 s: chapter before
            Triple(1, 200_500L, FilePosition(1, 0)),
            Triple(1, 1_000L, FilePosition(0, 0)), // first chapter of file 1: last of file 0
            Triple(0, 1_000L, FilePosition(0, 0)), // very first chapter: its own start
            Triple(0, 90_000L, FilePosition(0, 0)),
            Triple(2, 2_000L, FilePosition(1, 400_000))
        )
        for ((file, pos, expected) in table) {
            assertEquals(expected, embedded.previous(file, pos), "file=$file pos=$pos")
        }
    }

    @Test
    fun `position in chapter and chapter duration`() {
        assertEquals(50_000L, embedded.positionInChapterMs(1, 250_000))
        assertEquals(0L, embedded.positionInChapterMs(1, 200_000))
        assertEquals(30_000L, perFile.positionInChapterMs(2, 30_000))
        assertEquals(200_000L, embedded.chapterAt(1, 250_000)?.durationMs)
    }

    @Test
    fun `book position sums the preceding durations`() {
        assertEquals(0L, perFile.bookPositionMs(0, 0))
        assertEquals(1_200_000L + 5_000, perFile.bookPositionMs(2, 5_000))
        assertEquals(600_000L + 42, perFile.bookPositionMs(1, 42))
        assertEquals(1_800_000L, perFile.totalDurationMs)
    }

    @Test
    fun `unknown durations count as zero and make the total unknown`() {
        val unknown = ChapterTimeline(listOf(file(0), file(1, durationMs = null), file(2)), emptyList())
        assertEquals(600_000L + 0 + 7_000, unknown.bookPositionMs(2, 7_000))
        assertNull(unknown.totalDurationMs)
        assertNull(unknown.chapters[1].endMs)
        assertNull(unknown.chapters[1].durationMs)
    }

    @Test
    fun `locate is the inverse of bookPosition`() {
        val table = listOf(
            0L to FilePosition(0, 0),
            599_999L to FilePosition(0, 599_999),
            600_000L to FilePosition(1, 0),
            1_300_000L to FilePosition(2, 100_000),
            1_800_000L to FilePosition(2, 600_000), // end of the book
            9_999_999L to FilePosition(2, 600_000) // past the end: clamped
        )
        for ((bookPos, expected) in table) assertEquals(expected, perFile.locate(bookPos), "bookPos=$bookPos")
    }

    @Test
    fun `locate lands the remainder in the first file of unknown duration`() {
        val unknown = ChapterTimeline(listOf(file(0), file(1, durationMs = null), file(2)), emptyList())
        assertEquals(FilePosition(1, 100_000), unknown.locate(700_000))
        assertNull(ChapterTimeline(emptyList(), emptyList()).locate(0))
    }

    @Test
    fun `a chapter of an unknown file index is ignored`() {
        val timeline = ChapterTimeline(book().files, listOf(BookChapter(0, "X", 9, 0, null)))
        assertEquals(3, timeline.chapters.size)
    }

    // ---- offset (skips, rewind) ----

    @Test
    fun `offset inside a file`() {
        assertEquals(FilePosition(1, 130_000), perFile.offset(1, 120_000, 10_000))
        assertEquals(FilePosition(1, 110_000), perFile.offset(1, 120_000, -10_000))
        assertEquals(FilePosition(1, 120_000), perFile.offset(1, 120_000, 0))
    }

    @Test
    fun `offset crosses file boundaries both ways`() {
        assertEquals(FilePosition(0, 595_000), perFile.offset(1, 5_000, -10_000))
        assertEquals(FilePosition(0, 590_000), perFile.offset(1, 0, -10_000))
        assertEquals(FilePosition(1, 5_000), perFile.offset(0, 595_000, 10_000))
        assertEquals(FilePosition(2, 10_000), perFile.offset(0, 590_000, 620_000))
        assertEquals(FilePosition(0, 100_000), perFile.offset(2, 100_000, -1_200_000))
    }

    @Test
    fun `offset stops at the book's boundaries`() {
        assertEquals(FilePosition(0, 0), perFile.offset(0, 5_000, -30_000))
        assertEquals(FilePosition(0, 0), perFile.offset(2, 10_000, -9_999_999))
        assertEquals(FilePosition(2, 600_000), perFile.offset(2, 595_000, 30_000))
        assertEquals(FilePosition(2, 600_000), perFile.offset(0, 0, 99_999_999))
    }

    @Test
    fun `offset with an unknown duration does not guess`() {
        val files = listOf(file(0, durationMs = null), file(1), file(2, durationMs = null))
        val unknown = ChapterTimeline(files, emptyList())
        // Back across the unknown file 0: start of file 1.
        assertEquals(FilePosition(1, 0), unknown.offset(1, 5_000, -10_000))
        // Forward in an unknown-duration file: stays there, the player clamps.
        assertEquals(FilePosition(0, 700_000), unknown.offset(0, 600_000, 100_000))
        assertEquals(FilePosition(2, 40_000), unknown.offset(1, 590_000, 50_000))
    }
}
