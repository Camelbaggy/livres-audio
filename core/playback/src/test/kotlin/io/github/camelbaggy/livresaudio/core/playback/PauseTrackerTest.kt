package io.github.camelbaggy.livresaudio.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PauseTrackerTest {
    private var now = 1_000_000L
    private val tracker = PauseTracker { now }

    @Test
    fun `pause duration is measured then consumed once`() {
        tracker.onPlayWhenReadyChanged(false)
        now += 90_000
        assertTrue(tracker.isPaused)
        assertEquals(90_000L, tracker.consume())
        assertFalse(tracker.isPaused)
        assertEquals(0L, tracker.consume())
    }

    @Test
    fun `playing again does not start a pause`() {
        tracker.onPlayWhenReadyChanged(true)
        assertFalse(tracker.isPaused)
    }

    @Test
    fun `a loaded book starts its pause at lastPlayedAt, null means no pause`() {
        tracker.arm(now - 3 * 24 * 3_600_000L)
        assertEquals(3 * 24 * 3_600_000L, tracker.consume())
        tracker.arm(null)
        assertFalse(tracker.isPaused)
    }

    @Test
    fun `a transient interruption keeps its earliest instant even if the user pauses meanwhile`() {
        tracker.onSuppressed(true) // call starts
        now += 20_000
        tracker.onPlayWhenReadyChanged(false) // user pauses during the call
        now += 10_000
        assertEquals(30_000L, tracker.consume())
    }

    @Test
    fun `a new pause replaces the armed one`() {
        tracker.arm(now - 10_000_000)
        tracker.onPlayWhenReadyChanged(false)
        now += 7_000
        assertEquals(7_000L, tracker.consume())
    }

    @Test
    fun `clock set back never gives a negative pause`() {
        tracker.onPlayWhenReadyChanged(false)
        now -= 60_000
        assertEquals(0L, tracker.consume())
    }
}
