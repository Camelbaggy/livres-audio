package io.github.camelbaggy.livresaudio.core.playback

import androidx.media3.common.PlaybackParameters
import io.github.camelbaggy.livresaudio.core.model.ListeningSettings
import kotlin.math.roundToInt

/** Pure speed rules (CLAUDE.md §5.3): 0.5x to 3.0x in steps of 0.05, voice pitch kept. */
object PlaybackSpeed {
    const val MIN = ListeningSettings.MIN_SPEED
    const val MAX = ListeningSettings.MAX_SPEED
    const val STEP = ListeningSettings.SPEED_STEP

    /** Clamped to [MIN]..[MAX] and rounded to the nearest [STEP]; NaN/infinite give 1.0. */
    fun normalize(speed: Float): Float {
        if (!speed.isFinite()) return 1.0f
        val stepped = (speed / STEP).roundToInt() * STEP
        return stepped.coerceIn(MIN, MAX)
    }

    /** The speed of a book: its own override when it has one, else the profile's default. */
    fun effective(bookOverride: Float?, default: Float): Float = normalize(bookOverride ?: default)

    /** Pitch is always left at 1 so that voices keep their natural height. */
    fun parameters(speed: Float): PlaybackParameters = PlaybackParameters(normalize(speed), 1f)
}
