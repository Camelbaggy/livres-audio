package io.github.camelbaggy.livresaudio.core.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/** Memory buffer settings (CLAUDE.md §5.4): 2 min minimum, 10 min maximum, 60 s of back buffer kept. */
@UnstableApi
object PlaybackBuffers {
    const val MIN_BUFFER_MS = 2 * 60 * 1000
    const val MAX_BUFFER_MS = 10 * 60 * 1000
    const val BUFFER_FOR_PLAYBACK_MS = 2_500
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000
    const val BACK_BUFFER_MS = 60 * 1000

    fun createLoadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            MIN_BUFFER_MS,
            MAX_BUFFER_MS,
            BUFFER_FOR_PLAYBACK_MS,
            BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
        )
        // Audio only: the default byte ceiling would cut the buffer well before 10 minutes.
        .setPrioritizeTimeOverSizeThresholds(true)
        .setBackBuffer(BACK_BUFFER_MS, true)
        .build()
}
