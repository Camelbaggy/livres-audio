package io.github.camelbaggy.livresaudio.core.playback

import io.github.camelbaggy.livresaudio.core.model.ListeningStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PositionKeeperTest {
    private val theBook = book()
    private val timeline = ChapterTimeline(theBook.files, theBook.embeddedChapters)

    private class Setup(val keeper: PositionKeeper, val player: FakePlayer, val repo: FakeProgressRepository)

    private fun TestScope.setup(
        target: RestoreTarget = RestoreTarget(1, 120_000),
        saved: io.github.camelbaggy.livresaudio.core.data.db.ProgressEntity? = progress(),
        repo: FakeProgressRepository = FakeProgressRepository(saved),
        theBook: PlaybackBook = this@PositionKeeperTest.theBook
    ): Setup {
        val player = FakePlayer(theBook)
        val keeper = PositionKeeper(
            book = theBook,
            timeline = ChapterTimeline(theBook.files, theBook.embeddedChapters),
            initialTarget = target,
            repository = repo,
            scope = backgroundScope,
            writeDispatcher = StandardTestDispatcher(testScheduler),
            snapshot = { player.snap },
            clock = { testScheduler.currentTime }
        )
        return Setup(keeper, player, repo)
    }

    // ---- Gate: nothing is written before the player is at the restored position ----

    @Test
    fun `nothing is written while the player is not ready at the target`() = runTest {
        val s = setup()
        // Loading: buffering at the target.
        s.player.at(1, 120_000, KeeperPlayerState.BUFFERING)
        s.keeper.onPlaybackStateChanged()
        s.keeper.onIsPlayingChanged(false)
        s.keeper.onPlayWhenReadyChanged(false)
        s.keeper.onPositionDiscontinuity(DiscontinuityKind.OTHER)
        s.keeper.onPlayerError()
        // Ready but at position 0 (the restore failed): still nothing.
        s.player.at(1, 0)
        s.keeper.onPlaybackStateChanged()
        s.keeper.onIsPlayingChanged(true)
        advanceTimeBy(30_000)
        s.keeper.onIsPlayingChanged(false)
        // Ready at the target position but on another file.
        s.player.at(0, 120_000)
        s.keeper.onPlaybackStateChanged()
        s.keeper.onPlayerError()
        runCurrent()
        assertFalse(s.keeper.isRestored)
        assertEquals(emptyList(), s.repo.calls)
        assertEquals(120_000L, s.repo.state.value?.positionMs)
    }

    @Test
    fun `the gate opens when ready on the right file within tolerance, without writing by itself`() = runTest {
        val s = setup()
        s.player.at(1, 121_500)
        s.keeper.onPlaybackStateChanged()
        runCurrent()
        assertTrue(s.keeper.isRestored)
        assertEquals(emptyList(), s.repo.calls)
    }

    @Test
    fun `a foreign playlist never opens the gate nor receives saves`() = runTest {
        val s = setup()
        // Same index and position but another book's item.
        s.player.snap = PlayerSnapshot("other-book/f1", 1, 3, 120_000, KeeperPlayerState.READY, true)
        s.keeper.onPlaybackStateChanged()
        s.keeper.onIsPlayingChanged(true)
        advanceTimeBy(20_000)
        assertFalse(s.keeper.isRestored)
        assertEquals(emptyList(), s.repo.calls)

        // Once restored, a switch to a foreign item stops the saves too.
        s.player.at(1, 120_000)
        s.keeper.onPlaybackStateChanged()
        s.player.snap = s.player.snap.copy(mediaId = "other-book/f1")
        s.keeper.onPlayWhenReadyChanged(false)
        runCurrent()
        assertEquals(emptyList(), s.repo.calls)
    }

    @Test
    fun `a user seek before restoration replaces the target and is then saved`() = runTest {
        val s = setup()
        s.player.at(1, 120_000, KeeperPlayerState.BUFFERING)
        s.keeper.onPlaybackStateChanged()
        // The user jumps to file 2 at 30 s while still loading; the player is instantly ready there.
        s.player.at(2, 30_000)
        s.keeper.onPositionDiscontinuity(DiscontinuityKind.SEEK)
        runCurrent()
        assertTrue(s.keeper.isRestored)
        assertEquals(listOf(2 to 30_000L), s.repo.calls.map { it.fileIndex to it.positionMs })
        // 2 files of 10 min before + 30 s.
        assertEquals(1_230_000L, s.repo.calls.single().bookPositionMs)
    }

    @Test
    fun `an automatic change of file does not move the target`() = runTest {
        val s = setup()
        s.player.at(2, 5_000)
        s.keeper.onPositionDiscontinuity(DiscontinuityKind.OTHER)
        runCurrent()
        assertFalse(s.keeper.isRestored)
        assertEquals(emptyList(), s.repo.calls)
    }

    // ---- Saves ----

    @Test
    fun `saves every 5 seconds while playing and stops when paused`() = runTest {
        val s = setup()
        s.player.at(1, 120_000)
        s.keeper.onPlaybackStateChanged()
        s.player.at(1, 120_000, playing = true)
        s.keeper.onIsPlayingChanged(true)
        advanceTimeBy(4_999)
        assertEquals(0, s.repo.calls.size)
        s.player.at(1, 125_000, playing = true)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(125_000L), s.repo.calls.map { it.positionMs })
        s.player.at(1, 130_000, playing = true)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(125_000L, 130_000L), s.repo.calls.map { it.positionMs })
        // Pause: saves once more, then no more ticks.
        s.player.at(1, 132_000)
        s.keeper.onIsPlayingChanged(false)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf(125_000L, 130_000L, 132_000L), s.repo.calls.map { it.positionMs })
        assertTrue(s.repo.calls.all { it.status == ListeningStatus.IN_PROGRESS && !it.allowReset })
        assertEquals(720_000L + 12_000, s.repo.state.value?.bookPositionMs)
        assertEquals("md5-1", s.repo.state.value?.fileMd5)
    }

    @Test
    fun `saves on pause, seek, file change, focus loss and error`() = runTest {
        val s = setup()
        s.player.at(1, 120_000)
        s.keeper.onPlaybackStateChanged()

        s.player.at(1, 150_000)
        s.keeper.onIsPlayingChanged(false) // pause
        runCurrent()
        s.player.at(1, 300_000)
        s.keeper.onPositionDiscontinuity(DiscontinuityKind.SEEK)
        runCurrent()
        s.player.at(2, 0) // automatic change of file: position 0 of file 2, book position 1_200_000
        s.keeper.onPositionDiscontinuity(DiscontinuityKind.OTHER)
        runCurrent()
        s.player.at(2, 20_000)
        s.keeper.onPlayWhenReadyChanged(false) // audio focus loss / pause while buffering
        runCurrent()
        s.player.at(2, 40_000, KeeperPlayerState.IDLE)
        s.keeper.onPlayerError()
        runCurrent()

        assertEquals(
            listOf(1 to 150_000L, 1 to 300_000L, 2 to 0L, 2 to 20_000L, 2 to 40_000L),
            s.repo.calls.map { it.fileIndex to it.positionMs }
        )
        assertEquals(1_200_000L, s.repo.calls[2].bookPositionMs)
        // playWhenReady turning true is not a save event.
        s.keeper.onPlayWhenReadyChanged(true)
        runCurrent()
        assertEquals(5, s.repo.calls.size)
    }

    @Test
    fun `reaching the end of the last file is FINISHED, anything else IN_PROGRESS`() = runTest {
        val s = setup(target = RestoreTarget(2, 590_000), saved = progress(2, "md5-2", "f2", 590_000, 1_790_000))
        s.player.at(2, 590_000)
        s.keeper.onPlaybackStateChanged()
        s.player.at(2, 600_000, KeeperPlayerState.ENDED)
        s.keeper.onPlaybackStateChanged()
        runCurrent()
        val last = s.repo.calls.last()
        assertEquals(ListeningStatus.FINISHED, last.status)
        assertEquals(600_000L, last.positionMs)
        assertEquals(1_800_000L, last.bookPositionMs)
        assertEquals(ListeningStatus.FINISHED, s.repo.state.value?.status)

        // The user seeks back: IN_PROGRESS again.
        s.player.at(2, 100_000)
        s.keeper.onPositionDiscontinuity(DiscontinuityKind.SEEK)
        runCurrent()
        assertEquals(ListeningStatus.IN_PROGRESS, s.repo.calls.last().status)
    }

    // ---- Reset safeguards ----

    @Test
    fun `a zero position over a saved one is refused and never forced`() = runTest {
        // A finished book restarts at 0: target (0,0).
        val s =
            setup(
                target = RestoreTarget(0, 0),
                saved = progress(2, "md5-2", "f2", 600_000, 1_800_000, ListeningStatus.FINISHED)
            )
        s.player.at(0, 0)
        s.keeper.onPlaybackStateChanged()
        s.keeper.onIsPlayingChanged(false)
        s.keeper.onPlayWhenReadyChanged(false)
        runCurrent()
        assertTrue(s.repo.calls.isNotEmpty())
        assertTrue(s.repo.calls.all { !it.allowReset })
        assertEquals(s.repo.calls.size, s.keeper.refusedResets)
        assertEquals(1_800_000L, s.repo.state.value?.bookPositionMs)
        // Real listening later replaces it.
        s.player.at(0, 5_000, playing = true)
        s.keeper.onIsPlayingChanged(true)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(5_000L, s.repo.state.value?.positionMs)
        assertEquals(ListeningStatus.IN_PROGRESS, s.repo.state.value?.status)
    }

    @Test
    fun `explicit reset is the only write with allowReset`() = runTest {
        val s = setup()
        s.player.at(1, 120_000)
        s.keeper.onPlaybackStateChanged()
        s.keeper.resetToStart()
        // The service then seeks to the start; a periodic zero save must not undo the reset in the queue.
        s.player.at(0, 0)
        s.keeper.onPositionDiscontinuity(DiscontinuityKind.SEEK)
        runCurrent()
        assertTrue(s.keeper.isRestored)
        val reset = s.repo.calls.first()
        assertTrue(reset.allowReset)
        assertEquals(ListeningStatus.NOT_STARTED, reset.status)
        assertEquals(0L, s.repo.state.value?.positionMs)
        assertEquals(ListeningStatus.NOT_STARTED, s.repo.state.value?.status)
        assertTrue(s.repo.calls.drop(1).none { it.allowReset })
    }

    // ---- Serialization, failures, end of life ----

    @Test
    fun `writes are serialized and the last request wins`() = runTest {
        val repo = FakeProgressRepository(progress(), writeDelayMs = 1_000)
        val s = setup(repo = repo)
        s.player.at(1, 120_000)
        s.keeper.onPlaybackStateChanged()
        // A burst of events while the first write is in flight.
        for (position in listOf(130_000L, 140_000L, 150_000L, 160_000L)) {
            s.player.at(1, position)
            s.keeper.onPositionDiscontinuity(DiscontinuityKind.SEEK)
            advanceTimeBy(100)
        }
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, repo.maxConcurrent)
        assertTrue(repo.calls.size <= 2, "burst must be conflated, got ${repo.calls.size}")
        assertEquals(160_000L, repo.calls.last().positionMs)
        assertEquals(160_000L, repo.state.value?.positionMs)
    }

    @Test
    fun `a failed write is kept and written by the next flush`() = runTest {
        val repo = FakeProgressRepository(progress(), failTimes = 1)
        val s = setup(repo = repo)
        s.player.at(1, 120_000)
        s.keeper.onPlaybackStateChanged()
        s.player.at(1, 150_000)
        s.keeper.onIsPlayingChanged(false)
        runCurrent()
        assertEquals(1, s.keeper.failedWrites)
        assertEquals(120_000L, repo.state.value?.positionMs)
        s.keeper.flush()
        assertEquals(150_000L, repo.state.value?.positionMs)
    }

    @Test
    fun `finish writes the final position then ignores events`() = runTest {
        val s = setup()
        s.player.at(1, 120_000)
        s.keeper.onPlaybackStateChanged()
        s.player.at(1, 200_000, playing = true)
        s.keeper.finish()
        assertEquals(200_000L, s.repo.state.value?.positionMs)
        val writes = s.repo.calls.size
        s.player.at(1, 250_000)
        s.keeper.onIsPlayingChanged(false)
        s.keeper.onPositionDiscontinuity(DiscontinuityKind.SEEK)
        s.keeper.flush()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(writes, s.repo.calls.size)
    }

    @Test
    fun `finish before restoration writes nothing`() = runTest {
        val s = setup()
        s.player.at(1, 0, KeeperPlayerState.BUFFERING)
        s.keeper.finish()
        assertEquals(emptyList(), s.repo.calls)
        assertEquals(120_000L, s.repo.state.value?.positionMs)
    }

    @Test
    fun `flushBlocking writes the final position within its bound without the main thread`() {
        val repo = FakeProgressRepository(progress())
        val player = FakePlayer(theBook)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val keeper = PositionKeeper(
            book = theBook,
            timeline = timeline,
            initialTarget = RestoreTarget(1, 120_000),
            repository = repo,
            scope = scope,
            writeDispatcher = Dispatchers.IO,
            snapshot = { player.snap },
            clock = System::currentTimeMillis
        )
        player.at(1, 120_000)
        keeper.onPlaybackStateChanged()
        player.at(1, 777_000, playing = true)
        keeper.flushBlocking(timeoutMs = 2_000, close = true)
        assertEquals(777_000L, repo.state.value?.positionMs)
        scope.cancel()
    }

    @Test
    fun `flushBlocking before restoration writes nothing`() {
        val repo = FakeProgressRepository(progress())
        val player = FakePlayer(theBook)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val keeper =
            PositionKeeper(theBook, timeline, RestoreTarget(1, 120_000), repo, scope, Dispatchers.IO, {
                player.snap
            }, { 0 })
        player.at(1, 0)
        runBlocking { keeper.flushBlocking(close = true) }
        assertEquals(emptyList(), repo.calls)
        scope.cancel()
    }

    @Test
    fun `deep in a long file the tolerance grows with the target, but position 0 never opens the gate`() = runTest {
        val s = setup(target = RestoreTarget(1, 36_000_000))
        s.player.at(1, 0)
        s.keeper.onPlaybackStateChanged()
        assertFalse(s.keeper.isRestored)
        s.player.at(1, 36_180_000) // approximate seek: 3 min off at 10 h (0.5 %)
        s.keeper.onPlaybackStateChanged()
        assertTrue(s.keeper.isRestored)
    }
}
