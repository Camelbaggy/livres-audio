package io.github.camelbaggy.livresaudio.core.metadata

import kotlin.test.Test
import kotlin.test.assertEquals

class MetadataModuleTest {
    @Test
    fun moduleNameIsStable() {
        assertEquals("metadata", MetadataModule.NAME)
    }
}
