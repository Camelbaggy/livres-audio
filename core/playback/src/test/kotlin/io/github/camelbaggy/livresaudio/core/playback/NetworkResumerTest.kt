package io.github.camelbaggy.livresaudio.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkResumerTest {
    private class FakeTarget(
        override var playWhenReady: Boolean = true,
        override var hasError: Boolean = true,
        override var mediaId: String? = "book-1/f1"
    ) : ResumeTarget {
        var resumed = 0

        override fun resume() {
            resumed++
        }
    }

    private class Setup(val resumer: NetworkResumer, val target: FakeTarget, val network: FakeNetwork)

    private fun TestScope.setup(online: Boolean = false, target: FakeTarget = FakeTarget()): Setup {
        val network = FakeNetwork(online)
        val resumer = NetworkResumer(backgroundScope, network, target, onlineRetryDelayMs = 3_000)
        resumer.start()
        runCurrent()
        return Setup(resumer, target, network)
    }

    @Test
    fun `an error while offline resumes once when the network returns`() = runTest {
        val s = setup(online = false)
        s.resumer.onPlayerError(PlayerErrorKind.RECOVERABLE)
        runCurrent()
        assertEquals(0, s.target.resumed)
        s.network.set(true)
        runCurrent()
        assertEquals(1, s.target.resumed)
        // The network flaps again, the player is still in error: no second resume for the same failure.
        s.network.set(false)
        runCurrent()
        s.network.set(true)
        runCurrent()
        assertEquals(1, s.target.resumed)
    }

    @Test
    fun `a user pause during the outage cancels the resume`() = runTest {
        val s = setup()
        s.resumer.onPlayerError(PlayerErrorKind.RECOVERABLE)
        s.target.playWhenReady = false
        s.network.set(true)
        runCurrent()
        assertEquals(0, s.target.resumed)
        // Playing again by hand later must not be followed by a ghost resume at the next network return.
        s.target.playWhenReady = true
        s.network.set(false)
        runCurrent()
        s.network.set(true)
        runCurrent()
        assertEquals(0, s.target.resumed)
    }

    @Test
    fun `a change of book cancels the resume`() = runTest {
        val s = setup()
        s.resumer.onPlayerError(PlayerErrorKind.RECOVERABLE)
        s.target.mediaId = "book-2/f1"
        s.network.set(true)
        runCurrent()
        assertEquals(0, s.target.resumed)
    }

    @Test
    fun `no resume when the player is not in error any more`() = runTest {
        val s = setup()
        s.resumer.onPlayerError(PlayerErrorKind.RECOVERABLE)
        s.target.hasError = false // the user restarted it by hand
        s.network.set(true)
        runCurrent()
        assertEquals(0, s.target.resumed)
    }

    @Test
    fun `errors that waiting cannot fix, and users who were not playing, are left alone`() = runTest {
        val s = setup()
        s.resumer.onPlayerError(PlayerErrorKind.NOT_FOUND)
        s.network.set(true)
        runCurrent()
        s.network.set(false)
        runCurrent()
        s.resumer.onPlayerError(PlayerErrorKind.OTHER)
        s.network.set(true)
        runCurrent()
        s.network.set(false)
        runCurrent()
        s.target.playWhenReady = false
        s.resumer.onPlayerError(PlayerErrorKind.RECOVERABLE)
        s.network.set(true)
        runCurrent()
        assertEquals(0, s.target.resumed)
    }

    @Test
    fun `an error while the system says online is retried once after a short delay`() = runTest {
        val s = setup(online = true)
        s.resumer.onPlayerError(PlayerErrorKind.RECOVERABLE)
        advanceTimeBy(2_900)
        runCurrent()
        assertEquals(0, s.target.resumed)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(1, s.target.resumed)
        // It fails again while still online: no loop.
        s.resumer.onPlayerError(PlayerErrorKind.RECOVERABLE)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, s.target.resumed)
        // But a real network return acts on it.
        s.network.set(false)
        runCurrent()
        s.network.set(true)
        runCurrent()
        assertEquals(2, s.target.resumed)
    }

    @Test
    fun `once playing again, the next failure gets its own resume`() = runTest {
        val s = setup(online = true)
        s.resumer.onPlayerError(PlayerErrorKind.RECOVERABLE)
        advanceTimeBy(3_100)
        runCurrent()
        assertEquals(1, s.target.resumed)
        s.resumer.onPlaying()
        s.resumer.onPlayerError(PlayerErrorKind.RECOVERABLE)
        advanceTimeBy(3_100)
        runCurrent()
        assertEquals(2, s.target.resumed)
    }

    @Test
    fun `the online delay does not fire if the user paused or the network went away again`() = runTest {
        val s = setup(online = true)
        s.resumer.onPlayerError(PlayerErrorKind.RECOVERABLE)
        s.target.playWhenReady = false
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, s.target.resumed)
    }
}
