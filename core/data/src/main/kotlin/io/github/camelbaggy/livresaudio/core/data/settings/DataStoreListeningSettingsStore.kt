package io.github.camelbaggy.livresaudio.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.camelbaggy.livresaudio.core.model.ListeningSettings
import io.github.camelbaggy.livresaudio.core.model.ListeningSettingsStore
import io.github.camelbaggy.livresaudio.core.model.RewindStep
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [ListeningSettingsStore] on a dedicated Preferences DataStore (device-local; profile sync comes at lot 4).
 *
 * Reading is tolerant: a missing, unreadable or out-of-range value falls back to the contract default for that
 * field only, never throws (a corrupted table must not break playback). Writing sanitizes with the same rules,
 * so what is stored can always be read back.
 */
class DataStoreListeningSettingsStore(private val dataStore: DataStore<Preferences>) : ListeningSettingsStore {
    override val settings: Flow<ListeningSettings> = dataStore.data
        .catch { failure ->
            if (failure is IOException) emit(emptyPreferences()) else throw failure
        }
        .map(::read)

    override suspend fun update(transform: (ListeningSettings) -> ListeningSettings) {
        dataStore.edit { preferences -> write(preferences, transform(read(preferences))) }
    }

    @Serializable
    private data class StepDto(val minPauseMs: Long, val rewindMs: Long)

    private fun read(preferences: Preferences): ListeningSettings {
        val defaults = ListeningSettings()
        return ListeningSettings(
            rewindEnabled = preferences[REWIND_ENABLED] ?: defaults.rewindEnabled,
            rewindTable = preferences[REWIND_TABLE]?.let(::decodeTable) ?: defaults.rewindTable,
            skipForwardMs = preferences[SKIP_FORWARD_MS]?.takeIf { it > 0 } ?: defaults.skipForwardMs,
            skipBackMs = preferences[SKIP_BACK_MS]?.takeIf { it > 0 } ?: defaults.skipBackMs,
            defaultSpeed = preferences[DEFAULT_SPEED]?.takeIf { it.isFinite() }?.let(::sanitizeSpeed)
                ?: defaults.defaultSpeed,
            headsetButtonsAsChapters = preferences[HEADSET_AS_CHAPTERS] ?: defaults.headsetButtonsAsChapters
        )
    }

    private fun write(preferences: MutablePreferences, value: ListeningSettings) {
        val defaults = ListeningSettings()
        preferences[REWIND_ENABLED] = value.rewindEnabled
        preferences[REWIND_TABLE] = encodeTable(sanitizeTable(value.rewindTable) ?: defaults.rewindTable)
        preferences[SKIP_FORWARD_MS] = value.skipForwardMs.takeIf { it > 0 } ?: defaults.skipForwardMs
        preferences[SKIP_BACK_MS] = value.skipBackMs.takeIf { it > 0 } ?: defaults.skipBackMs
        preferences[DEFAULT_SPEED] =
            if (value.defaultSpeed.isFinite()) sanitizeSpeed(value.defaultSpeed) else defaults.defaultSpeed
        preferences[HEADSET_AS_CHAPTERS] = value.headsetButtonsAsChapters
    }

    private fun decodeTable(raw: String): List<RewindStep>? = try {
        sanitizeTable(json.decodeFromString<List<StepDto>>(raw).map { RewindStep(it.minPauseMs, it.rewindMs) })
    } catch (failure: Exception) {
        null // SerializationException, IllegalArgumentException: unreadable table -> default
    }

    private fun encodeTable(table: List<RewindStep>): String =
        json.encodeToString(table.map { StepDto(it.minPauseMs, it.rewindMs) })

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private val REWIND_ENABLED = booleanPreferencesKey("rewind_enabled")
        private val REWIND_TABLE = stringPreferencesKey("rewind_table")
        private val SKIP_FORWARD_MS = longPreferencesKey("skip_forward_ms")
        private val SKIP_BACK_MS = longPreferencesKey("skip_back_ms")
        private val DEFAULT_SPEED = floatPreferencesKey("default_speed")
        private val HEADSET_AS_CHAPTERS = booleanPreferencesKey("headset_buttons_as_chapters")

        /** File name (under `datastore/`) of the settings DataStore, distinct from the accounts one. */
        const val FILE_NAME = "listening_settings"

        /**
         * A usable table: non-negative values, sorted by threshold (sorted here, not rejected). Null when empty
         * or invalid (negative value): the caller uses the default table. An empty table would mean "no rewind":
         * that is the `rewindEnabled` switch, not a table.
         */
        fun sanitizeTable(table: List<RewindStep>): List<RewindStep>? {
            if (table.isEmpty() || table.any { it.minPauseMs < 0 || it.rewindMs < 0 }) return null
            return table.sortedBy { it.minPauseMs }
        }

        /** Clamped to [ListeningSettings.MIN_SPEED]..[ListeningSettings.MAX_SPEED], rounded to the 0.05 step. */
        fun sanitizeSpeed(speed: Float): Float {
            val stepped = (speed / ListeningSettings.SPEED_STEP).roundToInt() * ListeningSettings.SPEED_STEP
            return stepped.coerceIn(ListeningSettings.MIN_SPEED, ListeningSettings.MAX_SPEED)
        }
    }
}
