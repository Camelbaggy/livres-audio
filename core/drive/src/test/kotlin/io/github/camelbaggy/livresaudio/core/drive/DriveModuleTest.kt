package io.github.camelbaggy.livresaudio.core.drive

import kotlin.test.Test
import kotlin.test.assertEquals

class DriveModuleTest {
    @Test
    fun moduleNameIsStable() {
        assertEquals("drive", DriveModule.NAME)
    }
}
