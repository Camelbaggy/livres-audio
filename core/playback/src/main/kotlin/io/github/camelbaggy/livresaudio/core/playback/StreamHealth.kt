package io.github.camelbaggy.livresaudio.core.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the streaming layer tells the UI beyond the raw player state (CLAUDE.md §5.4), published by the service
 * through [StreamHealthHolder].
 *
 * @property waitingForNetwork the player is stalled, or failed, for lack of network: shown as "En attente du
 *   réseau…". The player itself keeps retrying (or is resumed by [NetworkResumer]); nothing is lost.
 * @property notFound the file is gone from Drive (HTTP 404): shown as "Fichier introuvable". Marking the book
 *   `MISSING` in Room is not done by the playback module (see the report of the task).
 * @property availableMs audio still available from the position (memory buffer + contiguous disk cache), only
 *   filled while [waitingForNetwork]; null otherwise.
 */
data class StreamHealth(
    val waitingForNetwork: Boolean = false,
    val notFound: Boolean = false,
    val availableMs: Long? = null
)

/** Kind of a player error, as far as streaming recovery is concerned. */
enum class PlayerErrorKind {
    NONE,

    /** Network trouble or a transient server status: worth resuming when the network is back. */
    RECOVERABLE,

    /** HTTP 404: the file does not exist any more. */
    NOT_FOUND,

    /** Anything else (token needing the user, corrupt data...): resuming would not help. */
    OTHER
}

/** Pure classification of player errors, on the same rules as the load error policy. */
@UnstableApi
object PlayerErrors {
    fun kindOf(error: Throwable?): PlayerErrorKind {
        if (error == null) return PlayerErrorKind.NONE
        val io = generateSequence(error) { it.cause }.take(MAX_DEPTH).filterIsInstance<IOException>().firstOrNull()
            ?: return PlayerErrorKind.OTHER
        return when (val failure = DriveLoadErrorHandlingPolicy.classify(io)) {
            is LoadFailure.Http -> when {
                failure.code == 404 -> PlayerErrorKind.NOT_FOUND
                LoadRetryRules.decide(failure, 1) is RetryDecision.RetryAfter -> PlayerErrorKind.RECOVERABLE
                else -> PlayerErrorKind.OTHER
            }

            LoadFailure.Network -> PlayerErrorKind.RECOVERABLE

            LoadFailure.AuthBlocked, LoadFailure.Other -> PlayerErrorKind.OTHER
        }
    }

    private const val MAX_DEPTH = 8
}

/** Raw inputs of [computeStreamHealth]. [availableMs] is evaluated lazily by the caller, only when needed. */
data class StreamInputs(
    val playbackState: Int,
    val playWhenReady: Boolean,
    val online: Boolean,
    /** A network load failure happened a moment ago (the loader is retrying), even though the OS says "online". */
    val recentLoadFailure: Boolean,
    val error: PlayerErrorKind
)

/**
 * Pure rule of the displayed stream state:
 * - waiting for the network = buffering while offline or while loads are failing, or failed with a recoverable
 *   error while the user wanted to play (the resumer is waiting for the network);
 * - not found = HTTP 404.
 * The available audio is only computed ([availableMs]) when the state is "waiting".
 */
fun computeStreamHealth(inputs: StreamInputs, availableMs: () -> Long): StreamHealth {
    if (inputs.error == PlayerErrorKind.NOT_FOUND) return StreamHealth(notFound = true)
    val stalled = inputs.playbackState == Player.STATE_BUFFERING && (!inputs.online || inputs.recentLoadFailure)
    val failedWhilePlaying = inputs.error == PlayerErrorKind.RECOVERABLE && inputs.playWhenReady
    return if (stalled || failedWhilePlaying) {
        StreamHealth(waitingForNetwork = true, availableMs = availableMs().coerceAtLeast(0))
    } else {
        StreamHealth()
    }
}

/** Short French description of an amount of audio: `45 s`, `12 min`, `1 h 05 min`. */
fun formatAvailableAudio(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    return when {
        hours > 0 -> "$hours h ${minutes.toString().padStart(2, '0')} min"
        minutes > 0 -> "$minutes min"
        else -> "${totalSeconds % 60} s"
    }
}

/**
 * Remembers when a network load failure last happened (written from ExoPlayer's loading thread by
 * [DriveLoadErrorHandlingPolicy], read from the main thread). It tells "the loader is retrying" apart from
 * "the system merely says offline", and expires by itself ([WINDOW_MS] > longest retry delay plus timeout).
 */
class LoadFailureSignal(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val last = AtomicLong(NEVER)

    fun report(failure: LoadFailure) {
        if (failure is LoadFailure.Network || (failure is LoadFailure.Http && failure.code != 404)) {
            last.set(clock())
        }
    }

    fun clear() = last.set(NEVER)

    fun isRecent(windowMs: Long = WINDOW_MS): Boolean {
        val at = last.get()
        return at != NEVER && clock() - at <= windowMs
    }

    companion object {
        const val WINDOW_MS = 25_000L
        private const val NEVER = Long.MIN_VALUE
    }
}

/**
 * In-process window on the stream state for the UI, like [ActiveBookHolder] (the UI and the service share the
 * process; a `MediaController` cannot carry this, and the notification is built by the service itself, see
 * [StatusNotificationProvider]).
 */
@Singleton
class StreamHealthHolder @Inject constructor() {
    private val mutable = MutableStateFlow(StreamHealth())
    val health: StateFlow<StreamHealth> = mutable.asStateFlow()

    internal fun set(health: StreamHealth) {
        mutable.value = health
    }
}
