package io.github.camelbaggy.livresaudio.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackBuffersTest {
    @Test
    fun matchesSpecification() {
        assertEquals(120_000, PlaybackBuffers.MIN_BUFFER_MS)
        assertEquals(600_000, PlaybackBuffers.MAX_BUFFER_MS)
        assertEquals(60_000, PlaybackBuffers.BACK_BUFFER_MS)
    }
}
