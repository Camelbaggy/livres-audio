package io.github.camelbaggy.livresaudio.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackModuleTest {
    @Test
    fun moduleNameIsStable() {
        assertEquals("playback", PlaybackModule.NAME)
    }
}
