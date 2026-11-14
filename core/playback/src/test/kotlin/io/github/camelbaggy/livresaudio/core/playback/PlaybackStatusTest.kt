package io.github.camelbaggy.livresaudio.core.playback

import androidx.media3.common.Player
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackStatusTest {
    private fun status(state: Int, playWhenReady: Boolean, online: Boolean, error: String? = null) =
        computePlaybackStatus(state, playWhenReady, online, error)

    @Test
    fun mapsPlayerStates() {
        assertEquals(PlaybackStatus.Playing, status(Player.STATE_READY, true, true))
        assertEquals(PlaybackStatus.Paused, status(Player.STATE_READY, false, true))
        assertEquals(PlaybackStatus.Paused, status(Player.STATE_IDLE, false, true))
        assertEquals(PlaybackStatus.Ended, status(Player.STATE_ENDED, true, true))
    }

    @Test
    fun bufferingDependsOnNetwork() {
        assertEquals(PlaybackStatus.Loading, status(Player.STATE_BUFFERING, true, online = true))
        assertEquals(PlaybackStatus.WaitingForNetwork, status(Player.STATE_BUFFERING, true, online = false))
    }

    @Test
    fun playingFromBufferStaysPlayingWhenOffline() {
        assertEquals(PlaybackStatus.Playing, status(Player.STATE_READY, true, online = false))
    }

    @Test
    fun errorWins() {
        assertEquals(PlaybackStatus.Error("x"), status(Player.STATE_BUFFERING, true, false, "x"))
    }
}
