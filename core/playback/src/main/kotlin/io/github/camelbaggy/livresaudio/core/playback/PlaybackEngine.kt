package io.github.camelbaggy.livresaudio.core.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import io.github.camelbaggy.livresaudio.core.model.DriveAuthProvider

/**
 * Process-wide HTTP chain and cache (singleton, see [PlaybackHiltModule]); builds the players. A player must be
 * used from the main thread.
 */
@UnstableApi
class PlaybackEngine(private val context: Context, auth: () -> DriveAuthProvider, val playbackCache: PlaybackCache) {
    private val httpClient = DriveDataSourceFactory.createHttpClient(auth)

    /** Shared player of trial E2. The real service ([LivresAudioPlaybackService]) owns its own: [createPlayer]. */
    val player: ExoPlayer by lazy { createPlayer() }

    /** A new player on the shared cache and HTTP chain. The caller owns it (main thread) and must release it. */
    fun createPlayer(): ExoPlayer {
        val dataSourceFactory = DriveDataSourceFactory.create(httpClient, playbackCache.cache)
        // Constant-bitrate seeking: MP3 files without a Xing/VBRI header would otherwise not be seekable.
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractors)
            .setLoadErrorHandlingPolicy(DriveLoadErrorHandlingPolicy())
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(PlaybackBuffers.createLoadControl())
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // Initial values only: the session's ChapterPlayer reads the live settings (task 1.8, CLAUDE.md §5.3).
            .setSeekForwardIncrementMs(DEFAULT_SEEK_FORWARD_MS)
            .setSeekBackIncrementMs(DEFAULT_SEEK_BACK_MS)
            .build()
    }

    /** Media item for one Drive file; the explicit cache key matches [DriveDataSourceFactory.cacheKeyFactory]. */
    fun mediaItem(ref: DriveMediaRef, title: String): MediaItem = MediaItem.Builder()
        .setUri(DriveMediaUri.build(ref))
        .setCustomCacheKey(DriveMediaUri.cacheKey(ref))
        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(title).build())
        .build()

    companion object {
        const val DEFAULT_SEEK_FORWARD_MS = 30_000L
        const val DEFAULT_SEEK_BACK_MS = 10_000L
    }
}
