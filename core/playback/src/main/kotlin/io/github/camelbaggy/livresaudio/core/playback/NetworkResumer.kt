package io.github.camelbaggy.livresaudio.core.playback

import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What [NetworkResumer] needs of the player; read and called on the player's thread. */
interface ResumeTarget {
    /** What the user wants: still true after an error, false once they paused. */
    val playWhenReady: Boolean

    /** The player is in an error state (idle with a `PlaybackException`). */
    val hasError: Boolean

    /** Id of the current item (`<bookId>/<driveFileId>`), null when nothing is loaded. */
    val mediaId: String?

    /** `prepare()` then `play()` through the player stack, so the rewind of the table applies. */
    fun resume()
}

/**
 * Resumes playback by itself after a player error caused by the network (CLAUDE.md §5.4), when the user was
 * playing. The position needs no care here: [PositionKeeper] saved it in the same `onPlayerError` callback,
 * before this class is told, and `prepare()` resumes where the error happened.
 *
 * Rules:
 * - armed only by a recoverable error ([PlayerErrorKind.RECOVERABLE]) while `playWhenReady`;
 * - fires at the next network return (offline -> online), or, if the error happened while the system says online,
 *   once after [onlineRetryDelayMs] (the network may have come back just before the error was reported);
 * - **at most one resume per error**: arming is consumed by the attempt, and the "online at error" path is
 *   allowed once until playback has actually run again ([onPlaying]); another error re-arms but only a new network
 *   return will act on it, so a persistent failure never loops;
 * - never if the user paused meanwhile (`playWhenReady` false), nor if the loaded book changed, nor if the player
 *   is no longer in error.
 *
 * The rewind on resume: [ResumeRewindPlayer] is told of the interruption when the error occurs (see
 * [ResumeRewindPlayer.markInterrupted]), so the whole outage counts as a pause and the table of CLAUDE.md §5.3
 * applies (< 5 s: nothing; 5 s - 1 min: 3 s; 1 - 5 min: 10 s...), once, never more.
 */
@UnstableApi
class NetworkResumer(
    private val scope: CoroutineScope,
    private val network: NetworkStatus,
    private val target: ResumeTarget,
    private val onlineRetryDelayMs: Long = ONLINE_RETRY_DELAY_MS
) {
    private var armedBookId: String? = null
    private var attemptedSincePlaying = false
    private var delayedAttempt: Job? = null
    private var collector: Job? = null

    /** Number of automatic resumes performed (diagnostics, tests). */
    var resumes = 0
        private set

    fun start() {
        if (collector != null) return
        collector = scope.launch {
            network.online.collect { online ->
                if (online) {
                    delayedAttempt?.cancel()
                    attempt()
                }
            }
        }
    }

    fun stop() {
        collector?.cancel()
        collector = null
        delayedAttempt?.cancel()
        armedBookId = null
    }

    /** Call from `onPlayerError`, after the position was saved. */
    fun onPlayerError(error: PlayerErrorKind) {
        delayedAttempt?.cancel()
        if (error != PlayerErrorKind.RECOVERABLE || !target.playWhenReady) {
            armedBookId = null
            return
        }
        armedBookId = target.mediaId?.let(BookPlaylist::bookIdOf)
        if (armedBookId != null && network.isOnlineNow() && !attemptedSincePlaying) {
            delayedAttempt = scope.launch {
                delay(onlineRetryDelayMs)
                attempt()
            }
        }
    }

    /** Call when the player really plays: the failure is over, one more resume is allowed for the next one. */
    fun onPlaying() {
        delayedAttempt?.cancel()
        armedBookId = null
        attemptedSincePlaying = false
    }

    private fun attempt() {
        val armed = armedBookId ?: return
        armedBookId = null // consumed, whatever happens
        val sameBook = target.mediaId?.let(BookPlaylist::bookIdOf) == armed
        if (!sameBook || !target.playWhenReady || !target.hasError) return
        attemptedSincePlaying = true
        resumes++
        target.resume()
    }

    companion object {
        const val ONLINE_RETRY_DELAY_MS = 3_000L
    }
}
