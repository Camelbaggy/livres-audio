package io.github.camelbaggy.livresaudio.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.camelbaggy.livresaudio.core.model.ListeningSettings
import io.github.camelbaggy.livresaudio.core.model.ListeningSettingsStore
import io.github.camelbaggy.livresaudio.core.model.RewindStep
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val MIN_SKIP_SECONDS = 5
const val MAX_SKIP_SECONDS = 120
const val MAX_REWIND_SECONDS = 120

/** Skip duration kept in 5..120 s. Pure. */
fun clampSkipSeconds(seconds: Int): Long = seconds.coerceIn(MIN_SKIP_SECONDS, MAX_SKIP_SECONDS) * 1_000L

/** Speed kept in 0.5..3.0 and snapped to the 0.05 step. Pure. */
fun snapSpeed(speed: Float): Float {
    val clamped = speed.coerceIn(ListeningSettings.MIN_SPEED, ListeningSettings.MAX_SPEED)
    val steps = (clamped / ListeningSettings.SPEED_STEP).roundToInt()
    return (steps * ListeningSettings.SPEED_STEP).coerceIn(ListeningSettings.MIN_SPEED, ListeningSettings.MAX_SPEED)
        .let { (it * 100).roundToInt() / 100f }
}

/** Replaces the rewind of step [index] (0..120 s); other steps and thresholds are untouched. Pure. */
fun withStepRewind(table: List<RewindStep>, index: Int, rewindSeconds: Int): List<RewindStep> =
    table.mapIndexed { i, step ->
        if (i == index) step.copy(rewindMs = rewindSeconds.coerceIn(0, MAX_REWIND_SECONDS) * 1_000L) else step
    }

@HiltViewModel
class ListeningSettingsViewModel @Inject constructor(private val store: ListeningSettingsStore) : ViewModel() {
    val settings: StateFlow<ListeningSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListeningSettings())

    private fun edit(transform: (ListeningSettings) -> ListeningSettings) {
        viewModelScope.launch { store.update(transform) }
    }

    fun setRewindEnabled(enabled: Boolean) = edit { it.copy(rewindEnabled = enabled) }

    fun setStepRewindSeconds(index: Int, seconds: Int) =
        edit { it.copy(rewindTable = withStepRewind(it.rewindTable, index, seconds)) }

    fun resetRewindTable() = edit { it.copy(rewindTable = ListeningSettings.DEFAULT_REWIND_TABLE) }

    fun setSkipBackSeconds(seconds: Int) = edit { it.copy(skipBackMs = clampSkipSeconds(seconds)) }

    fun setSkipForwardSeconds(seconds: Int) = edit { it.copy(skipForwardMs = clampSkipSeconds(seconds)) }

    fun setDefaultSpeed(speed: Float) = edit { it.copy(defaultSpeed = snapSpeed(speed)) }

    fun setHeadsetButtonsAsChapters(asChapters: Boolean) = edit { it.copy(headsetButtonsAsChapters = asChapters) }
}
