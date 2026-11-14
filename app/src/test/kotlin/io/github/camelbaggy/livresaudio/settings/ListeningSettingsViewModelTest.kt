package io.github.camelbaggy.livresaudio.settings

import io.github.camelbaggy.livresaudio.core.model.ListeningSettings
import io.github.camelbaggy.livresaudio.library.FakeSettingsStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class ListeningSettingsViewModelTest {
    private lateinit var store: FakeSettingsStore
    private lateinit var viewModel: ListeningSettingsViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        store = FakeSettingsStore()
        viewModel = ListeningSettingsViewModel(store)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `les sauts sont bornes entre 5 et 120 secondes`() {
        viewModel.setSkipBackSeconds(1)
        assertEquals(5_000, store.flow.value.skipBackMs)
        viewModel.setSkipBackSeconds(999)
        assertEquals(120_000, store.flow.value.skipBackMs)
        viewModel.setSkipForwardSeconds(45)
        assertEquals(45_000, store.flow.value.skipForwardMs)
        viewModel.setSkipForwardSeconds(-3)
        assertEquals(5_000, store.flow.value.skipForwardMs)
    }

    @Test
    fun `la vitesse par defaut est bornee et alignee sur le pas de 0,05`() {
        viewModel.setDefaultSpeed(0.1f)
        assertEquals(0.5f, store.flow.value.defaultSpeed)
        viewModel.setDefaultSpeed(9f)
        assertEquals(3.0f, store.flow.value.defaultSpeed)
        viewModel.setDefaultSpeed(1.27f)
        assertEquals(1.25f, store.flow.value.defaultSpeed)
        viewModel.setDefaultSpeed(1.5f)
        assertEquals(1.5f, store.flow.value.defaultSpeed)
    }

    @Test
    fun `edition d'une marche de la table de retour arriere`() {
        viewModel.setStepRewindSeconds(2, 15)
        assertEquals(15_000, store.flow.value.rewindTable[2].rewindMs)
        assertEquals(ListeningSettings.DEFAULT_REWIND_TABLE[2].minPauseMs, store.flow.value.rewindTable[2].minPauseMs)
        assertEquals(ListeningSettings.DEFAULT_REWIND_TABLE[3], store.flow.value.rewindTable[3])
        viewModel.setStepRewindSeconds(2, 500)
        assertEquals(120_000, store.flow.value.rewindTable[2].rewindMs)
        viewModel.resetRewindTable()
        assertEquals(ListeningSettings.DEFAULT_REWIND_TABLE, store.flow.value.rewindTable)
    }

    @Test
    fun `activation du retour arriere et boutons du casque`() {
        viewModel.setRewindEnabled(false)
        viewModel.setHeadsetButtonsAsChapters(true)
        assertEquals(false, store.flow.value.rewindEnabled)
        assertEquals(true, store.flow.value.headsetButtonsAsChapters)
    }
}
