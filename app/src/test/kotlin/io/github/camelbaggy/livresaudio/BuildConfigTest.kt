package io.github.camelbaggy.livresaudio

import kotlin.test.Test
import kotlin.test.assertEquals

class BuildConfigTest {
    @Test
    fun applicationIdIsFrozen() {
        // The application id is bound to the Google OAuth client and must never change.
        assertEquals("io.github.camelbaggy.livresaudio", LivresAudioApp::class.java.`package`?.name)
    }
}
