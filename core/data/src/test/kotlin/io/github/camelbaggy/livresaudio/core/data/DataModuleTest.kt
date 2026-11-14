package io.github.camelbaggy.livresaudio.core.data

import kotlin.test.Test
import kotlin.test.assertEquals

class DataModuleTest {
    @Test
    fun moduleNameIsStable() {
        assertEquals("data", DataModule.NAME)
    }
}
