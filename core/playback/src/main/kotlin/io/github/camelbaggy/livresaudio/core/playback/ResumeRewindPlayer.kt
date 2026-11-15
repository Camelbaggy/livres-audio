package io.github.camelbaggy.livresaudio.core.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.camelbaggy.livresaudio.core.model.ListeningSettings

/**
 * Remembers since when playback has been stopped, to compute the rewind on resume (CLAUDE.md §5.3). Pure state
 * machine, no Android type: events are fed by [ResumeRewindPlayer], time comes from the injected [clock] (wall
 * clock in production, because the first play after loading a book uses `lastPlayedAt` of Room).
 *
 * "Stopped" = `playWhenReady` turned false (user pause, headset unplugged, permanent focus loss) or playback
 * suppressed by a transient focus loss (phone call, navigation announcement). In the second case the earliest
 * instant is kept even if the user then pauses.
 */
class PauseTracker(private val clock: () -> Long) {
    private var pausedAtMs: Long? = null
    private var keepEarliest = false

    /** True while a pause is known (a resume would rewind). */
    val isPaused: Boolean get() = pausedAtMs != null

    /** A book was just loaded: the pause started at its `lastPlayedAt` (null = never played: no rewind). */
    fun arm(lastPlayedAtMs: Long?) {
        pausedAtMs = lastPlayedAtMs
        keepEarliest = false
    }

    fun onPlayWhenReadyChanged(playWhenReady: Boolean) {
        if (playWhenReady) return
        if (pausedAtMs == null || !keepEarliest) pausedAtMs = clock()
    }

    /** Playback was interrupted by a failure while the user wanted to play (network error): counts as a pause. */
    fun onInterrupted() = onSuppressed(true)

    fun onSuppressed(suppressed: Boolean) {
        if (!suppressed) return
        if (pausedAtMs == null || !keepEarliest) pausedAtMs = clock()
        keepEarliest = true
    }

    /** Duration of the pause being ended (0 if unknown); forgets it, so a resume is rewound only once. */
    fun consume(): Long {
        val since = pausedAtMs ?: return 0
        pausedAtMs = null
        keepEarliest = false
        return (clock() - since).coerceAtLeast(0)
    }
}

/**
 * Rewinds a little when playback resumes, whatever the command that resumes it (screen, notification, Bluetooth,
 * car): this is the player the session exposes (wrapped by [ChapterPlayer], which is the outermost one). The
 * rewind may cross file boundaries and never goes before the start of the book ([ChapterTimeline.offset]).
 *
 * Triggers: `play()` / `setPlayWhenReady(true)` (applied *before* playback restarts), and, as a fallback, the
 * end of a transient audio-focus suppression or any `playWhenReady = true` that did not go through us.
 *
 * Interaction with [PositionKeeper]: the rewind is a plain `seekTo`, hence a SEEK discontinuity. Before the
 * keeper's restoration gate opens it only moves the restore target (nothing is written); afterwards the new
 * position is saved like any seek. The keeper needs no change.
 */
@UnstableApi
class ResumeRewindPlayer(
    private val player: Player,
    private val timeline: () -> ChapterTimeline?,
    private val settings: () -> ListeningSettings,
    clock: () -> Long = System::currentTimeMillis
) : ForwardingPlayer(player) {
    /** Exposed to arm it when a book is loaded ([PauseTracker.arm]) and for tests. */
    val tracker = PauseTracker(clock)

    internal val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) rewindNow() else tracker.onPlayWhenReadyChanged(false)
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                tracker.onSuppressed(true)
            } else if (player.playWhenReady) {
                rewindNow()
            }
        }
    }

    init {
        player.addListener(playerListener)
    }

    /** Detaches from the wrapped player (service shutdown). */
    fun detach() {
        player.removeListener(playerListener)
    }

    /**
     * A player error (network cut) interrupted playback while the user wanted to play: from now on the outage
     * counts as a pause, so the resume (automatic or by hand) rewinds according to the table, once.
     */
    fun markInterrupted() {
        tracker.onInterrupted()
    }

    override fun play() {
        rewindNow()
        retryIfFailed()
        super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) {
            rewindNow()
            retryIfFailed()
        }
        super.setPlayWhenReady(playWhenReady)
    }

    /** A player in error sits idle: playing again needs a `prepare()` (it resumes at the position of the error). */
    private fun retryIfFailed() {
        if (player.playerError != null && player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    /** Applies the rewind due for the pause being ended, once. */
    private fun rewindNow() {
        if (!tracker.isPaused) return
        val pauseMs = tracker.consume()
        val chapters = timeline() ?: return
        if (player.playbackState == Player.STATE_ENDED || player.mediaItemCount == 0) return
        val rewindMs = RewindPolicy.rewindFor(pauseMs, settings())
        if (rewindMs <= 0) return
        val target = chapters.offset(player.currentMediaItemIndex, player.currentPosition, -rewindMs)
        player.seekTo(target.fileIndex, target.positionMs)
    }
}
