package io.github.camelbaggy.livresaudio.core.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import io.github.camelbaggy.livresaudio.core.data.db.ProgressEntity
import io.github.camelbaggy.livresaudio.core.model.ListeningSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ResumeRewindPlayerTest {
    private val theBook = book() // 3 files of 10 min
    private val timeline = ChapterTimeline(theBook.files, theBook.embeddedChapters)
    private var now = 10_000_000L
    private var settings = ListeningSettings()

    private fun exo(): ExoPlayer {
        val exo = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        exo.setMediaItems(
            theBook.files.map {
                MediaItem.Builder()
                    .setMediaId(BookPlaylist.fileMediaId(theBook.bookId, it.driveFileId))
                    .setUri("drive://a/${it.driveFileId}")
                    .build()
            }
        )
        return exo
    }

    private fun wrap(exo: ExoPlayer) = ResumeRewindPlayer(exo, { timeline }, { settings }, { now })

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `resume rewinds inside the file by the table step`() {
        val exo = exo()
        val player = wrap(exo)
        exo.seekTo(1, 120_000)
        player.tracker.arm(now - 2 * 60_000) // 2 min pause -> 10 s
        player.play()
        assertEquals(1, exo.currentMediaItemIndex)
        assertEquals(110_000L, exo.currentPosition)
        assertTrue(exo.playWhenReady)
        exo.release()
    }

    @Test
    fun `a rewind past the start of a file goes back into the previous file`() {
        val exo = exo()
        val player = wrap(exo)
        exo.seekTo(1, 5_000)
        player.tracker.arm(now - 30 * 60_000) // 30 min -> 20 s
        player.play()
        assertEquals(0, exo.currentMediaItemIndex)
        assertEquals(585_000L, exo.currentPosition)
        exo.release()
    }

    @Test
    fun `the rewind stops at the start of the book`() {
        val exo = exo()
        val player = wrap(exo)
        exo.seekTo(0, 2_000)
        player.tracker.arm(now - 3 * 24 * 3_600_000L) // > 24 h -> 60 s
        player.play()
        assertEquals(0, exo.currentMediaItemIndex)
        assertEquals(0L, exo.currentPosition)
        exo.release()
    }

    @Test
    fun `first play after loading uses the last listening date, more than 24 h included`() {
        val exo = exo()
        val player = wrap(exo)
        exo.seekTo(2, 100_000)
        player.tracker.arm(now - 2 * 24 * 3_600_000L)
        player.setPlayWhenReady(true)
        assertEquals(40_000L, exo.currentPosition) // 60 s back
        exo.release()
    }

    @Test
    fun `no rewind when disabled, when never played or for a short pause`() {
        val exo = exo()
        val player = wrap(exo)
        exo.seekTo(1, 120_000)
        settings = ListeningSettings(rewindEnabled = false)
        player.tracker.arm(now - 3_600_000)
        player.play()
        assertEquals(120_000L, exo.currentPosition)

        settings = ListeningSettings()
        player.tracker.arm(null)
        player.play()
        assertEquals(120_000L, exo.currentPosition)

        player.tracker.arm(now - 4_000)
        player.play()
        assertEquals(120_000L, exo.currentPosition)
        exo.release()
    }

    @Test
    fun `a rewind is applied once per pause`() {
        val exo = exo()
        val player = wrap(exo)
        exo.seekTo(1, 120_000)
        player.tracker.arm(now - 2 * 60_000)
        player.play()
        player.play()
        assertEquals(110_000L, exo.currentPosition)
        exo.release()
    }

    @Test
    fun `a real pause is measured through the player's events`() {
        val exo = exo()
        val player = wrap(exo)
        exo.seekTo(1, 120_000)
        exo.playWhenReady = true
        idle()
        player.pause()
        idle()
        now += 90_000 // 90 s -> 10 s
        player.play()
        assertEquals(110_000L, exo.currentPosition)
        exo.release()
    }

    @Test
    fun `end of a transient focus loss rewinds without any play command`() {
        val exo = exo()
        val player = wrap(exo)
        exo.seekTo(1, 120_000)
        exo.playWhenReady = true
        idle()
        player.playerListener.onPlaybackSuppressionReasonChanged(
            Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
        )
        now += 20_000 // announcement of 20 s -> 3 s
        player.playerListener.onPlaybackSuppressionReasonChanged(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
        assertEquals(117_000L, exo.currentPosition)
        exo.release()
    }

    @Test
    fun `a short interruption is not rewound`() {
        val exo = exo()
        val player = wrap(exo)
        exo.seekTo(1, 120_000)
        exo.playWhenReady = true
        idle()
        player.playerListener.onPlaybackSuppressionReasonChanged(
            Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
        )
        now += 2_000
        player.playerListener.onPlaybackSuppressionReasonChanged(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
        assertEquals(120_000L, exo.currentPosition)
        assertFalse(player.tracker.isPaused)
        exo.release()
    }

    @Test
    fun `without a timeline resume does not move`() {
        val exo = exo()
        val player = ResumeRewindPlayer(exo, { null }, { settings }, { now })
        exo.seekTo(1, 120_000)
        player.tracker.arm(now - 3_600_000)
        player.play()
        assertEquals(120_000L, exo.currentPosition)
        exo.release()
    }

    // ---- Network failure: the outage counts as a pause, and play() brings a failed player back ----

    /** The real player, reporting a playback error and counting `prepare()` calls instead of running them. */
    private class FailedPlayer(exo: ExoPlayer) : androidx.media3.common.ForwardingPlayer(exo) {
        var prepared = 0

        override fun getPlayerError() = androidx.media3.common.PlaybackException(
            "réseau",
            java.io.IOException(),
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        )

        override fun prepare() {
            prepared++
        }
    }

    @Test
    fun `an outage counts as a pause and the automatic resume rewinds by the table, once`() {
        val exo = exo()
        val failed = FailedPlayer(exo)
        val player = ResumeRewindPlayer(failed, { timeline }, { settings }, { now })
        exo.seekTo(1, 120_000)
        exo.playWhenReady = true
        player.markInterrupted() // the service does this in onPlayerError
        now += 3 * 60_000 // 3 min without network -> 10 s
        player.play()
        assertEquals(110_000L, exo.currentPosition)
        assertEquals(1, failed.prepared)
        assertTrue(exo.playWhenReady)
        player.play()
        assertEquals(110_000L, exo.currentPosition) // a second play() does not rewind again
        exo.release()
    }

    @Test
    fun `a short outage resumes in place`() {
        val exo = exo()
        val failed = FailedPlayer(exo)
        val player = ResumeRewindPlayer(failed, { timeline }, { settings }, { now })
        exo.seekTo(1, 120_000)
        exo.playWhenReady = true
        player.markInterrupted()
        now += 3_000
        player.play()
        assertEquals(120_000L, exo.currentPosition)
        assertEquals(1, failed.prepared)
        exo.release()
    }

    @Test
    fun `an outage longer than a day rewinds by the largest step only`() {
        val exo = exo()
        val player = ResumeRewindPlayer(FailedPlayer(exo), { timeline }, { settings }, { now })
        exo.seekTo(2, 300_000)
        exo.playWhenReady = true
        player.markInterrupted()
        now += 30 * 3_600_000L
        player.play()
        assertEquals(240_000L, exo.currentPosition) // 60 s, never more than the table's maximum
        exo.release()
    }

    // ---- Interaction with PositionKeeper (unchanged): the rewind is a SEEK ----

    private class KeeperSetup(val keeper: PositionKeeper, val player: FakePlayer, val repo: FakeProgressRepository)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.keeperSetup(saved: ProgressEntity?, target: RestoreTarget): KeeperSetup {
        val fake = FakePlayer(theBook)
        val repo = FakeProgressRepository(saved)
        val keeper = PositionKeeper(
            book = theBook,
            timeline = timeline,
            initialTarget = target,
            repository = repo,
            scope = backgroundScope,
            writeDispatcher = StandardTestDispatcher(testScheduler),
            snapshot = { fake.snap },
            clock = { testScheduler.currentTime }
        )
        return KeeperSetup(keeper, fake, repo)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a rewind before the restoration gate writes nothing and is saved once restored`() = runTest {
        val s = keeperSetup(progress(), RestoreTarget(1, 120_000))
        // Loading: buffering at the saved position; the user presses play, the rewind seeks 10 s back.
        s.player.at(1, 120_000, KeeperPlayerState.BUFFERING)
        s.keeper.onPlaybackStateChanged()
        val back = timeline.offset(1, 120_000, RewindPolicy.rewindFor(2 * 60_000, ListeningSettings()).unaryMinus())
        s.player.at(back.fileIndex, back.positionMs, KeeperPlayerState.BUFFERING)
        s.keeper.onPositionDiscontinuity(DiscontinuityKind.SEEK)
        s.keeper.onPlayWhenReadyChanged(false)
        runCurrent()
        assertEquals(emptyList(), s.repo.calls) // nothing before restoration
        assertEquals(120_000L, s.repo.state.value?.positionMs) // the saved position is untouched

        // Ready at the rewound position: the gate opens; the next save carries the rewound position.
        s.player.at(back.fileIndex, back.positionMs, KeeperPlayerState.READY)
        s.keeper.onPlaybackStateChanged()
        assertTrue(s.keeper.isRestored)
        s.keeper.onPlayWhenReadyChanged(false)
        runCurrent()
        assertEquals(listOf(1 to 110_000L), s.repo.calls.map { it.fileIndex to it.positionMs })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a rewind crossing into the previous file is saved after restoration`() = runTest {
        val s = keeperSetup(progress(positionMs = 5_000, bookPositionMs = 605_000), RestoreTarget(1, 5_000))
        s.player.at(1, 5_000)
        s.keeper.onPlaybackStateChanged()
        assertTrue(s.keeper.isRestored)
        val back = timeline.offset(1, 5_000, -20_000)
        s.player.at(back.fileIndex, back.positionMs, KeeperPlayerState.READY)
        s.keeper.onPositionDiscontinuity(DiscontinuityKind.SEEK)
        runCurrent()
        val call = s.repo.calls.single()
        assertEquals(0, call.fileIndex)
        assertEquals(585_000L, call.positionMs)
        assertEquals(585_000L, call.bookPositionMs)
    }
}
