package io.github.camelbaggy.livresaudio.core.playback

import io.github.camelbaggy.livresaudio.core.model.ListeningSettings
import io.github.camelbaggy.livresaudio.core.model.RewindStep
import kotlin.test.Test
import kotlin.test.assertEquals

class RewindPolicyTest {
    private val s = 1_000L
    private val min = 60 * s
    private val h = 60 * min

    @Test
    fun `default table at every boundary`() {
        val cases = listOf(
            0L to 0L,
            4_900L to 0L,
            4_999L to 0L,
            5 * s to 3 * s,
            59 * s to 3 * s,
            59_999L to 3 * s,
            1 * min to 10 * s,
            4 * min + 59 * s to 10 * s,
            5 * min to 20 * s,
            59 * min to 20 * s,
            1 * h to 30 * s,
            23 * h + 59 * min to 30 * s,
            24 * h to 60 * s,
            30 * 24 * h to 60 * s
        )
        for ((pause, expected) in cases) {
            assertEquals(expected, RewindPolicy.rewindFor(pause, ListeningSettings()), "pause $pause")
        }
    }

    @Test
    fun `disabled means no rewind whatever the pause`() {
        val off = ListeningSettings(rewindEnabled = false)
        assertEquals(0L, RewindPolicy.rewindFor(48 * h, off))
    }

    @Test
    fun `custom table, even unsorted`() {
        val custom = ListeningSettings(
            rewindTable = listOf(RewindStep(30 * s, 15 * s), RewindStep(0, 1 * s), RewindStep(10 * min, 45 * s))
        )
        assertEquals(1 * s, RewindPolicy.rewindFor(29_999, custom))
        assertEquals(15 * s, RewindPolicy.rewindFor(30 * s, custom))
        assertEquals(15 * s, RewindPolicy.rewindFor(9 * min, custom))
        assertEquals(45 * s, RewindPolicy.rewindFor(10 * min, custom))
    }

    @Test
    fun `empty table, no reached step or negative pause give 0`() {
        assertEquals(0L, RewindPolicy.rewindFor(1 * h, ListeningSettings(rewindTable = emptyList())))
        val late = ListeningSettings(rewindTable = listOf(RewindStep(1 * min, 5 * s)))
        assertEquals(0L, RewindPolicy.rewindFor(59 * s, late))
        assertEquals(0L, RewindPolicy.rewindFor(-5 * min, ListeningSettings()))
    }
}
