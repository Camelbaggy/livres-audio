package io.github.camelbaggy.livresaudio.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncModuleTest {
    @Test
    fun moduleNameIsStable() {
        assertEquals("sync", SyncModule.NAME)
    }
}
