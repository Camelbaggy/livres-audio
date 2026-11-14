package io.github.camelbaggy.livresaudio.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.camelbaggy.livresaudio.core.model.ListeningSettings
import io.github.camelbaggy.livresaudio.core.model.RewindStep
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class DataStoreListeningSettingsStoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File.createTempFile("settings", "").also {
        it.delete()
        it.mkdirs()
    }

    private fun newDataStore(name: String = "s") =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "$name.preferences_pb") })

    @org.junit.After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `defaults when nothing is stored`() = runBlocking {
        val store = DataStoreListeningSettingsStore(newDataStore())
        assertEquals(ListeningSettings(), store.settings.first())
    }

    @Test
    fun `update round trip including a custom table`() = runBlocking {
        val ds = newDataStore()
        val store = DataStoreListeningSettingsStore(ds)
        val custom = ListeningSettings(
            rewindEnabled = false,
            rewindTable = listOf(RewindStep(0, 0), RewindStep(10_000, 7_000)),
            skipForwardMs = 45_000,
            skipBackMs = 15_000,
            defaultSpeed = 1.25f,
            headsetButtonsAsChapters = true
        )
        store.update { custom }
        assertEquals(custom, store.settings.first())
        // Another instance on the same file reads the same thing (persisted, not cached in memory).
        assertEquals(custom, DataStoreListeningSettingsStore(ds).settings.first())
    }

    @Test
    fun `update transforms the current value`() = runBlocking {
        val store = DataStoreListeningSettingsStore(newDataStore())
        store.update { it.copy(skipBackMs = 20_000) }
        store.update { it.copy(defaultSpeed = 1.5f) }
        val read = store.settings.first()
        assertEquals(20_000L, read.skipBackMs)
        assertEquals(1.5f, read.defaultSpeed)
        assertEquals(30_000L, read.skipForwardMs)
    }

    @Test
    fun `unreadable or invalid values fall back to the default field by field`() = runBlocking {
        val ds = newDataStore()
        ds.edit {
            it[stringPreferencesKey("rewind_table")] = "pas du json {"
            it[longPreferencesKey("skip_forward_ms")] = -5
            it[longPreferencesKey("skip_back_ms")] = 12_000
            it[floatPreferencesKey("default_speed")] = Float.NaN
        }
        val read = DataStoreListeningSettingsStore(ds).settings.first()
        assertEquals(ListeningSettings.DEFAULT_REWIND_TABLE, read.rewindTable)
        assertEquals(30_000L, read.skipForwardMs)
        assertEquals(12_000L, read.skipBackMs)
        assertEquals(1.0f, read.defaultSpeed)
    }

    @Test
    fun `table with a negative step or empty is replaced by the default`() = runBlocking {
        val ds = newDataStore()
        val store = DataStoreListeningSettingsStore(ds)
        ds.edit { it[stringPreferencesKey("rewind_table")] = """[{"minPauseMs":-1,"rewindMs":3000}]""" }
        assertEquals(ListeningSettings.DEFAULT_REWIND_TABLE, store.settings.first().rewindTable)
        ds.edit { it[stringPreferencesKey("rewind_table")] = "[]" }
        assertEquals(ListeningSettings.DEFAULT_REWIND_TABLE, store.settings.first().rewindTable)
        store.update { it.copy(rewindTable = emptyList()) }
        assertEquals(ListeningSettings.DEFAULT_REWIND_TABLE, store.settings.first().rewindTable)
    }

    @Test
    fun `unsorted table is sorted and unknown json keys are ignored`() = runBlocking {
        val ds = newDataStore()
        ds.edit {
            it[stringPreferencesKey("rewind_table")] =
                """[{"minPauseMs":60000,"rewindMs":9000,"x":1},{"minPauseMs":0,"rewindMs":0}]"""
        }
        val table = DataStoreListeningSettingsStore(ds).settings.first().rewindTable
        assertEquals(listOf(RewindStep(0, 0), RewindStep(60_000, 9_000)), table)
    }

    @Test
    fun `speed is clamped and rounded to the step`() {
        val cases = listOf(0.1f to 0.5f, 5f to 3.0f, 1.07f to 1.05f, 1.43f to 1.45f, 0.5f to 0.5f, 3.0f to 3.0f)
        for ((input, expected) in cases) {
            assertEquals(expected, DataStoreListeningSettingsStore.sanitizeSpeed(input), 0.0001f, "speed $input")
        }
        assertNotNull(DataStoreListeningSettingsStore.sanitizeTable(ListeningSettings.DEFAULT_REWIND_TABLE))
    }
}
