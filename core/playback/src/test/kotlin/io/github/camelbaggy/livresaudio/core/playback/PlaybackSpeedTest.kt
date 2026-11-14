package io.github.camelbaggy.livresaudio.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackSpeedTest {
    @Test
    fun `normalize clamps and rounds to 0_05`() {
        val cases = listOf(
            0.1f to 0.5f,
            0.5f to 0.5f,
            0.52f to 0.5f,
            0.53f to 0.55f,
            1.0f to 1.0f,
            1.07f to 1.05f,
            1.43f to 1.45f,
            2.999f to 3.0f,
            3.0f to 3.0f,
            9f to 3.0f,
            Float.NaN to 1.0f,
            Float.POSITIVE_INFINITY to 1.0f
        )
        for ((input, expected) in cases) {
            assertEquals(expected, PlaybackSpeed.normalize(input), 0.0001f, "speed $input")
        }
    }

    @Test
    fun `book override wins over the default`() {
        assertEquals(1.5f, PlaybackSpeed.effective(1.5f, 1.0f), 0.0001f)
        assertEquals(1.2f, PlaybackSpeed.effective(null, 1.2f), 0.0001f)
    }

    @Test
    fun `pitch is never changed`() {
        val parameters = PlaybackSpeed.parameters(1.75f)
        assertEquals(1.75f, parameters.speed, 0.0001f)
        assertEquals(1f, parameters.pitch)
    }
}
