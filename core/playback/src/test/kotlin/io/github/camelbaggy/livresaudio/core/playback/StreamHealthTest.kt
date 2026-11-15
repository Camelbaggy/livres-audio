package io.github.camelbaggy.livresaudio.core.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import io.github.camelbaggy.livresaudio.core.drive.rest.AuthFailedException
import io.github.camelbaggy.livresaudio.core.model.AuthResult
import java.net.ConnectException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals

@UnstableApi
class StreamHealthTest {
    private fun inputs(
        state: Int = Player.STATE_READY,
        playWhenReady: Boolean = true,
        online: Boolean = true,
        recentFailure: Boolean = false,
        error: PlayerErrorKind = PlayerErrorKind.NONE
    ) = StreamInputs(state, playWhenReady, online, recentFailure, error)

    private fun health(i: StreamInputs, available: Long = 720_000) = computeStreamHealth(i) { available }

    @Test
    fun `healthy playback says nothing, and does not even compute the available audio`() {
        var computed = false
        val h = computeStreamHealth(inputs()) {
            computed = true
            1
        }
        assertEquals(StreamHealth(), h)
        assertEquals(false, computed)
        assertEquals(StreamHealth(), health(inputs(state = Player.STATE_BUFFERING)))
    }

    @Test
    fun `buffering offline or while loads fail is waiting for the network, with the available audio`() {
        val offline = health(inputs(state = Player.STATE_BUFFERING, online = false))
        assertEquals(StreamHealth(waitingForNetwork = true, availableMs = 720_000), offline)
        val failing = health(inputs(state = Player.STATE_BUFFERING, recentFailure = true), available = 5_000)
        assertEquals(StreamHealth(waitingForNetwork = true, availableMs = 5_000), failing)
    }

    @Test
    fun `playing from the buffer while offline is not yet a problem`() {
        assertEquals(StreamHealth(), health(inputs(state = Player.STATE_READY, online = false)))
    }

    @Test
    fun `a recoverable error while the user wanted to play is waiting, a paused user is not`() {
        val failed = inputs(state = Player.STATE_IDLE, error = PlayerErrorKind.RECOVERABLE)
        assertEquals(true, health(failed).waitingForNetwork)
        assertEquals(false, health(failed.copy(playWhenReady = false)).waitingForNetwork)
    }

    @Test
    fun `404 is file not found, whatever else is going on`() {
        val h = health(inputs(state = Player.STATE_BUFFERING, online = false, error = PlayerErrorKind.NOT_FOUND))
        assertEquals(StreamHealth(notFound = true), h)
    }

    @Test
    fun `available audio is never negative`() {
        assertEquals(0L, health(inputs(state = Player.STATE_BUFFERING, online = false), available = -5).availableMs)
    }

    @Test
    fun `audio amounts are spoken in French style`() {
        val table = mapOf(
            0L to "0 s",
            45_000L to "45 s",
            59_999L to "59 s",
            60_000L to "1 min",
            12 * 60_000L to "12 min",
            65 * 60_000L to "1 h 05 min",
            -3L to "0 s"
        )
        table.forEach { (ms, text) -> assertEquals(text, formatAvailableAudio(ms), "$ms ms") }
    }

    // ---- Error classification ----

    private val dataSpec = blankDataSpec()

    private fun playbackError(cause: Throwable) =
        PlaybackException("x", cause, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

    private fun http(code: Int) =
        HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), dataSpec, ByteArray(0))

    @Test
    fun `player errors are classified like load errors`() {
        val open = HttpDataSource.HttpDataSourceException.TYPE_OPEN
        val table = mapOf(
            "no error" to (null to PlayerErrorKind.NONE),
            "unknown host" to (playbackError(UnknownHostException()) to PlayerErrorKind.RECOVERABLE),
            "connect" to (playbackError(ConnectException()) to PlayerErrorKind.RECOVERABLE),
            "http exception, no code" to (
                playbackError(
                    HttpDataSource.HttpDataSourceException.createForIOException(ConnectException(), dataSpec, open)
                ) to PlayerErrorKind.RECOVERABLE
                ),
            "503" to (playbackError(http(503)) to PlayerErrorKind.RECOVERABLE),
            "404" to (playbackError(http(404)) to PlayerErrorKind.NOT_FOUND),
            "403" to (playbackError(http(403)) to PlayerErrorKind.OTHER),
            "token needs the user" to (
                playbackError(AuthFailedException(AuthResult.NeedsUserAction)) to PlayerErrorKind.OTHER
                ),
            "token, no network" to (
                playbackError(AuthFailedException(AuthResult.NetworkUnavailable)) to PlayerErrorKind.RECOVERABLE
                ),
            "no io cause at all" to (
                PlaybackException("x", null, PlaybackException.ERROR_CODE_DECODING_FAILED) to
                    PlayerErrorKind.OTHER
                )
        )
        table.forEach { (name, case) -> assertEquals(case.second, PlayerErrors.kindOf(case.first), name) }
    }

    // ---- Load failure signal ----

    @Test
    fun `the load failure signal is recent for a while after a network failure only`() {
        var now = 1_000_000L
        val signal = LoadFailureSignal { now }
        assertEquals(false, signal.isRecent())
        signal.report(LoadFailure.Http(404)) // gone for good: not a network problem
        signal.report(LoadFailure.Other)
        assertEquals(false, signal.isRecent())
        signal.report(LoadFailure.Network)
        assertEquals(true, signal.isRecent())
        now += LoadFailureSignal.WINDOW_MS - 1
        assertEquals(true, signal.isRecent())
        now += 2
        assertEquals(false, signal.isRecent())
        signal.report(LoadFailure.Http(503))
        assertEquals(true, signal.isRecent())
        signal.clear()
        assertEquals(false, signal.isRecent())
    }
}
