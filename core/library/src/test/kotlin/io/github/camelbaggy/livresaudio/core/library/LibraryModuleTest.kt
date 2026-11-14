package io.github.camelbaggy.livresaudio.core.library

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryModuleTest {
    @Test
    fun moduleNameIsStable() {
        assertEquals("library", LibraryModule.NAME)
    }
}
