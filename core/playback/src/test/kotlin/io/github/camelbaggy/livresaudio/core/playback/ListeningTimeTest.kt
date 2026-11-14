package io.github.camelbaggy.livresaudio.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListeningTimeTest {
    // 3 files of 600 s; file 1 has chapters at 0, 200 s (end 400 s) and 400 s.
    private val theBook = book(
        chapters = listOf(
            BookChapter(0, "A", 1, 0, 200_000),
            BookChapter(1, "B", 1, 200_000, null),
            BookChapter(2, "C", 1, 400_000, null)
        )
    )
    private val timeline = ChapterTimeline(theBook.files, theBook.embeddedChapters)

    @Test
    fun `book remaining time depends on the speed`() {
        // 1800 s total, at 1 min into file 1 -> 660 s elapsed -> 1140 s of audio left.
        assertEquals(1_140_000L, ListeningTime.bookRemainingMs(timeline, 1, 60_000, 1.0f))
        assertEquals(760_000L, ListeningTime.bookRemainingMs(timeline, 1, 60_000, 1.5f))
        assertEquals(570_000L, ListeningTime.bookRemainingMs(timeline, 1, 60_000, 2.0f))
    }

    @Test
    fun `book remaining is null when a duration is unknown, 0 at the end`() {
        val unknown = ChapterTimeline(listOf(file(0), file(1, durationMs = null)), emptyList())
        assertNull(ListeningTime.bookRemainingMs(unknown, 0, 0, 1.0f))
        assertEquals(0L, ListeningTime.bookRemainingMs(timeline, 2, 600_000, 1.0f))
        assertEquals(0L, ListeningTime.bookRemainingMs(timeline, 2, 900_000, 1.0f))
    }

    @Test
    fun `chapter remaining and duration`() {
        // Chapter B of file 1: 200 s to 400 s (end taken from chapter C's start).
        assertEquals(150_000L, ListeningTime.chapterRemainingMs(timeline, 1, 250_000, 1.0f))
        assertEquals(100_000L, ListeningTime.chapterRemainingMs(timeline, 1, 250_000, 1.5f))
        assertEquals(200_000L, ListeningTime.chapterDurationMs(timeline, 1, 250_000))
        // Whole-file chapter of file 0.
        assertEquals(500_000L, ListeningTime.chapterRemainingMs(timeline, 0, 100_000, 1.0f))
    }

    @Test
    fun `chapter end falls back to the player's file duration`() {
        val unknown = ChapterTimeline(listOf(file(0, durationMs = null)), emptyList())
        assertNull(ListeningTime.chapterRemainingMs(unknown, 0, 10_000, 1.0f))
        assertEquals(50_000L, ListeningTime.chapterRemainingMs(unknown, 0, 10_000, 1.0f, fileDurationMs = 60_000))
        assertEquals(60_000L, ListeningTime.chapterDurationMs(unknown, 0, 10_000, fileDurationMs = 60_000))
    }
}
