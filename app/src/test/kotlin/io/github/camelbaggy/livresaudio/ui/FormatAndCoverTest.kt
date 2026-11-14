package io.github.camelbaggy.livresaudio.ui

import io.github.camelbaggy.livresaudio.util.formatClock
import io.github.camelbaggy.livresaudio.util.formatDuration
import io.github.camelbaggy.livresaudio.util.formatSpeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FormatAndCoverTest {
    @Test
    fun `formats de temps`() {
        assertEquals("0:05", formatClock(5_400))
        assertEquals("1:01:01", formatClock(3_661_000))
        assertEquals("0:00", formatClock(-5))
        assertEquals("3 h 05 min", formatDuration(11_100_000))
        assertEquals("45 min", formatDuration(2_700_000))
        assertEquals("30 s", formatDuration(30_000))
    }

    @Test
    fun `format de vitesse a la francaise`() {
        assertEquals("1", formatSpeed(1.0f))
        assertEquals("1,25", formatSpeed(1.25f))
        assertEquals("0,5", formatSpeed(0.5f))
        assertEquals("1,05", formatSpeed(1.05f))
        assertEquals("3", formatSpeed(3.0f))
    }

    @Test
    fun `couverture generee`() {
        assertEquals("FD", coverInitials("Fortune de France"))
        assertEquals("É", coverInitials("étonnant"))
        assertEquals("?", coverInitials(null))
        assertEquals("?", coverInitials("  --  "))
        assertEquals(coverHue("abc"), coverHue("abc"))
        assertTrue(coverHue("un-identifiant-long") in 0f..359f)
    }

    @Test
    fun `schemas de couverture pris en charge`() {
        assertTrue(isDisplayableCover("https://exemple.org/c.jpg"))
        assertTrue(isDisplayableCover("content://media/1"))
        assertTrue(isDisplayableCover("file:///data/c.jpg"))
        assertFalse(isDisplayableCover("drive-abc123"))
        assertFalse(isDisplayableCover("drive://abc"))
        assertFalse(isDisplayableCover(null))
        assertFalse(isDisplayableCover(""))
    }
}
