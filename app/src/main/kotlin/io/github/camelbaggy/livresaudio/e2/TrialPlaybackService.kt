package io.github.camelbaggy.livresaudio.e2

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import io.github.camelbaggy.livresaudio.core.playback.PlaybackEngine
import javax.inject.Inject

/**
 * Minimal service of trial E2: wraps the singleton player in a media session so that playback survives a
 * switched-off screen, with a system notification and Bluetooth/lock-screen controls. Temporary: the real
 * `MediaLibraryService` is task 1.6.
 */
@UnstableApi
@AndroidEntryPoint
class TrialPlaybackService : MediaSessionService() {
    @Inject
    lateinit var engine: PlaybackEngine

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession.Builder(this, engine.player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        // Releases the session only; the player belongs to the singleton engine.
        session?.release()
        session = null
        super.onDestroy()
    }
}
