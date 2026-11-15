package io.github.camelbaggy.livresaudio.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Non-regression of the golden rule through a network failure and the automatic resume: the real [PositionKeeper]
 * and [NetworkResumer] driven by a scripted player, in the order the service calls them from `onPlayerError`
 * (keeper FIRST).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamRecoveryTest {
    private val theBook = book()
    private val timeline = ChapterTimeline(theBook.files, theBook.embeddedChapters)

    private class Rig(
        val keeper: PositionKeeper,
        val resumer: NetworkResumer,
        val player: FakePlayer,
        val repo: FakeProgressRepository,
        val network: FakeNetwork,
        val resumed: MutableList<Long>
    )

    private fun TestScope.rig(
        saved: io.github.camelbaggy.livresaudio.core.data.db.ProgressEntity,
        target: RestoreTarget
    ): Rig {
        val player = FakePlayer(theBook)
        val repo = FakeProgressRepository(saved)
        val keeper = PositionKeeper(
            book = theBook,
            timeline = timeline,
            initialTarget = target,
            repository = repo,
            scope = backgroundScope,
            writeDispatcher = StandardTestDispatcher(testScheduler),
            snapshot = { player.snap },
            clock = { testScheduler.currentTime }
        )
        val network = FakeNetwork(online = true)
        val resumed = mutableListOf<Long>()
        val resumeTarget = object : ResumeTarget {
            override val playWhenReady = true
            override val hasError get() = player.snap.state == KeeperPlayerState.IDLE
            override val mediaId get() = player.snap.mediaId

            override fun resume() {
                // prepare() then play(): the player comes back at the position of the error.
                resumed += player.snap.positionMs
                player.at(player.snap.mediaItemIndex, player.snap.positionMs, KeeperPlayerState.BUFFERING)
                keeper.onPlaybackStateChanged()
                player.at(player.snap.mediaItemIndex, player.snap.positionMs, KeeperPlayerState.READY, playing = true)
                keeper.onPlaybackStateChanged()
                keeper.onIsPlayingChanged(true)
            }
        }
        val resumer = NetworkResumer(backgroundScope, network, resumeTarget, onlineRetryDelayMs = 3_000)
        resumer.start()
        return Rig(keeper, resumer, player, repo, network, resumed)
    }

    /** What the service's `onPlayerError` does, in its order. */
    private fun Rig.fail(kind: PlayerErrorKind = PlayerErrorKind.RECOVERABLE) {
        player.snap = player.snap.copy(state = KeeperPlayerState.IDLE, isPlaying = false)
        keeper.onPlayerError()
        resumer.onPlayerError(kind)
    }

    @Test
    fun `the position at the time of the error is saved before the resume and survives it`() = runTest {
        val r = rig(progress(), RestoreTarget(1, 120_000))
        r.player.at(1, 120_000, playing = true)
        r.keeper.onPlaybackStateChanged()
        r.keeper.onIsPlayingChanged(true)
        advanceTimeBy(20_000) // saved every 5 s while playing
        r.player.at(1, 140_000, playing = true)
        advanceTimeBy(5_000)
        r.network.set(false)
        r.player.at(1, 163_000, playing = true)
        r.fail()
        runCurrent()
        // The keeper wrote the position of the error, before anything tried to resume.
        assertEquals(163_000L, r.repo.state.value?.positionMs)
        assertEquals(0, r.resumed.size)

        advanceTimeBy(120_000) // two minutes offline
        r.network.set(true)
        runCurrent()
        assertEquals(listOf(163_000L), r.resumed)
        // The player came back at the same position and keeps playing: positions only move forward.
        r.player.at(1, 170_000, playing = true)
        advanceTimeBy(10_000)
        runCurrent()
        val positions = r.repo.calls.map { it.positionMs }
        assertTrue(positions.all { it >= 120_000 }, "positions: $positions")
        assertEquals(positions.sorted(), positions)
        assertEquals(0, r.repo.calls.count { it.bookPositionMs == 0L || it.positionMs == 0L })
        assertEquals(0, r.keeper.refusedResets)
        assertEquals(170_000L, r.repo.state.value?.positionMs)
    }

    @Test
    fun `an error before the restoration is complete writes nothing, resume or not`() = runTest {
        val r = rig(progress(), RestoreTarget(1, 120_000))
        // The first load failed: the player is idle at 0, never reached the target.
        r.player.at(1, 0, KeeperPlayerState.BUFFERING)
        r.keeper.onPlaybackStateChanged()
        r.network.set(false)
        r.fail()
        runCurrent()
        r.network.set(true)
        runCurrent()
        assertFalse(r.keeper.isRestored)
        assertEquals(emptyList(), r.repo.calls)
        assertEquals(120_000L, r.repo.state.value?.positionMs)
    }

    @Test
    fun `a failure that is not about the network is not resumed and keeps its position`() = runTest {
        val r = rig(progress(), RestoreTarget(1, 120_000))
        r.player.at(1, 120_000, playing = true)
        r.keeper.onPlaybackStateChanged()
        r.player.at(1, 130_000, playing = true)
        r.fail(PlayerErrorKind.NOT_FOUND)
        r.network.set(false)
        runCurrent()
        r.network.set(true)
        runCurrent()
        assertEquals(0, r.resumed.size)
        assertEquals(130_000L, r.repo.state.value?.positionMs)
    }
}
