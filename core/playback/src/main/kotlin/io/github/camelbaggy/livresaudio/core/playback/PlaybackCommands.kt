package io.github.camelbaggy.livresaudio.core.playback

import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture

/**
 * Custom session commands of the playback service, for the UI (CLAUDE.md §5.3). A `MediaController` sends them
 * with `sendCustomCommand` (see the extensions below); the service accepts them from any connected controller.
 */
object PlaybackCommands {
    /**
     * Sets the speed of the loaded book and remembers it as this book's override. Argument: [ARG_SPEED] (float,
     * clamped to 0.5-3.0 and rounded to 0.05; see [PlaybackSpeed]).
     */
    const val SET_SPEED = "io.github.camelbaggy.livresaudio.SET_SPEED"

    /** Removes the book's speed override: the book follows the profile's default speed again. No argument. */
    const val CLEAR_SPEED_OVERRIDE = "io.github.camelbaggy.livresaudio.CLEAR_SPEED_OVERRIDE"

    /** Bundle key of the speed (float) of [SET_SPEED]. */
    const val ARG_SPEED = "speed"

    val setSpeed = SessionCommand(SET_SPEED, Bundle.EMPTY)
    val clearSpeedOverride = SessionCommand(CLEAR_SPEED_OVERRIDE, Bundle.EMPTY)

    fun speedArgs(speed: Float): Bundle = Bundle().apply { putFloat(ARG_SPEED, speed) }
}

/** Sets this book's speed ([PlaybackCommands.SET_SPEED]); the player's speed is visible in `playbackParameters`. */
fun MediaController.sendSetSpeed(speed: Float): ListenableFuture<SessionResult> =
    sendCustomCommand(PlaybackCommands.setSpeed, PlaybackCommands.speedArgs(speed))

/** Back to the default speed for this book ([PlaybackCommands.CLEAR_SPEED_OVERRIDE]). */
fun MediaController.sendClearSpeedOverride(): ListenableFuture<SessionResult> =
    sendCustomCommand(PlaybackCommands.clearSpeedOverride, Bundle.EMPTY)
