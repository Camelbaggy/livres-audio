package io.github.camelbaggy.livresaudio.e2

import kotlin.test.Test
import kotlin.test.assertEquals

class E2SupportTest {
    @Test
    fun escapesApostrophesAndBackslashes() {
        assertEquals("l\\'été", escapeDriveQueryValue("l'été"))
        assertEquals("a\\\\b", escapeDriveQueryValue("a\\b"))
        assertEquals("\\\\\\'", escapeDriveQueryValue("\\'"))
    }

    @Test
    fun buildsAudioQuery() {
        assertEquals(
            "mimeType contains 'audio/' and name contains 'fortune' and trashed=false",
            audioSearchQuery("  fortune ")
        )
        assertEquals(
            "mimeType contains 'audio/' and name contains 'l\\'enfant' and trashed=false",
            audioSearchQuery("l'enfant")
        )
        assertEquals("mimeType contains 'audio/' and trashed=false", audioSearchQuery("   "))
    }

    @Test
    fun formatsDurations() {
        val table = mapOf(
            0L to "00:00",
            59_999L to "00:59",
            61_000L to "01:01",
            3_600_000L to "1:00:00",
            3_725_000L to "1:02:05",
            40_000_000L to "11:06:40"
        )
        table.forEach { (ms, expected) -> assertEquals(expected, formatDuration(ms), "$ms ms") }
        assertEquals("--:--", formatDuration(null))
        assertEquals("--:--", formatDuration(-1))
    }

    @Test
    fun formatsMegabytes() {
        assertEquals("0,0 Mo", formatMegabytes(0))
        assertEquals("312,5 Mo", formatMegabytes(312_500_000))
        assertEquals("? Mo", formatMegabytes(null))
    }
}
