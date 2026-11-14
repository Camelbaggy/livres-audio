package io.github.camelbaggy.livresaudio.core.playback

import io.github.camelbaggy.livresaudio.core.model.ListeningSettings

/** Pure rewind-on-resume rule (CLAUDE.md §5.3). */
object RewindPolicy {
    /**
     * How far to step back when resuming after a pause of [pauseMs]: the `rewindMs` of the last
     * table step whose threshold is reached; 0 when rewinding is disabled, the table is empty or no step is reached.
     * The table is not assumed sorted. A negative pause (clock set back) counts as 0.
     */
    fun rewindFor(pauseMs: Long, settings: ListeningSettings): Long {
        if (!settings.rewindEnabled) return 0
        val pause = pauseMs.coerceAtLeast(0)
        return settings.rewindTable
            .filter { it.minPauseMs <= pause }
            .maxByOrNull { it.minPauseMs }
            ?.rewindMs
            ?.coerceAtLeast(0)
            ?: 0
    }
}
