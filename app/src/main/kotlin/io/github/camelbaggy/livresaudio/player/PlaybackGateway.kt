package io.github.camelbaggy.livresaudio.player

import androidx.media3.common.Player
import io.github.camelbaggy.livresaudio.core.playback.ActiveBook
import kotlinx.coroutines.flow.StateFlow

/** What the UI knows of the player, read from the `MediaController` (no Media3 type exposed). */
data class ControllerState(
    val connected: Boolean = false,
    val fileIndex: Int = 0,
    val positionMs: Long = 0,
    val fileDurationMs: Long? = null,
    val playbackState: Int = Player.STATE_IDLE,
    val playWhenReady: Boolean = false,
    val speed: Float = 1f,
    /** Short description of a player error, null when none. */
    val error: String? = null,
    val online: Boolean = true
)

/** Playback commands of the UI. Implemented over the `MediaController` ([MediaControllerHolder]). */
interface PlaybackGateway {
    val state: StateFlow<ControllerState>

    /** Loads [bookId] in the service (unless it already is) and starts playing from the saved position. */
    fun playBook(bookId: String)

    fun play()

    fun pause()

    fun seekTo(fileIndex: Int, positionMs: Long)

    /** Moves by [deltaMs] (negative = back), across file boundaries. */
    fun seekBy(deltaMs: Long)

    /** Deliberate reset of the loaded book to its start (service command `COMMAND_RESET_POSITION`). */
    fun resetPosition()
}

/** Speed commands, sent to the service as session commands (`SET_SPEED`, `CLEAR_SPEED_OVERRIDE`). */
interface PlaybackCommands {
    fun setSpeed(speed: Float)

    fun clearSpeedOverride()
}

/** The book loaded in the service (wraps `ActiveBookHolder`; an interface so ViewModels are testable). */
interface ActiveBookSource {
    val active: StateFlow<ActiveBook?>
}
