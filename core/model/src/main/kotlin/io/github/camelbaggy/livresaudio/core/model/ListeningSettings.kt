package io.github.camelbaggy.livresaudio.core.model

import kotlinx.coroutines.flow.Flow

/** One row of the rewind-on-resume table: a pause of at least [minPauseMs] rewinds by [rewindMs] (CLAUDE.md §5.3). */
data class RewindStep(val minPauseMs: Long, val rewindMs: Long)

/** Listening settings of the profile (synced in `profile.json` at lot 4; local for now). */
data class ListeningSettings(
    val rewindEnabled: Boolean = true,
    /** Sorted by [RewindStep.minPauseMs]; the last step whose threshold is reached applies. */
    val rewindTable: List<RewindStep> = DEFAULT_REWIND_TABLE,
    val skipForwardMs: Long = 30_000,
    val skipBackMs: Long = 10_000,
    /** 0.5 to 3.0, step 0.05. */
    val defaultSpeed: Float = 1.0f,
    /** Headset next/previous buttons: false = skips (default), true = chapters. */
    val headsetButtonsAsChapters: Boolean = false
) {
    companion object {
        private const val SECOND = 1_000L
        private const val MINUTE = 60 * SECOND
        private const val HOUR = 60 * MINUTE

        /** Default table of CLAUDE.md §5.3. */
        val DEFAULT_REWIND_TABLE: List<RewindStep> = listOf(
            RewindStep(0, 0),
            RewindStep(5 * SECOND, 3 * SECOND),
            RewindStep(1 * MINUTE, 10 * SECOND),
            RewindStep(5 * MINUTE, 20 * SECOND),
            RewindStep(1 * HOUR, 30 * SECOND),
            RewindStep(24 * HOUR, 60 * SECOND)
        )

        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f
        const val SPEED_STEP = 0.05f
    }
}

/** Device-local store of [ListeningSettings] (DataStore). Profile sync comes at lot 4. */
interface ListeningSettingsStore {
    val settings: Flow<ListeningSettings>

    suspend fun update(transform: (ListeningSettings) -> ListeningSettings)
}
