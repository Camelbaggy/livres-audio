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
 * Process-wide player and cache (singleton, see [PlaybackHiltModule]). [player] must be used from the main
 * thread. Temporary shape for trial E2; task 1.6 moves the player into a `MediaLibraryService`.
 */
@UnstableApi
class PlaybackEngine(private val context: Context, auth: () -> DriveAuthProvider, val playbackCache: PlaybackCache) {
    private val httpClient = DriveDataSourceFactory.createHttpClient(auth)

    val player: ExoPlayer by lazy { buildPlayer() }

    private fun buildPlayer(): ExoPlayer {
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
            .build()
    }

    /** Media item for one Drive file; the explicit cache key matches [DriveDataSourceFactory.cacheKeyFactory]. */
    fun mediaItem(ref: DriveMediaRef, title: String): MediaItem = MediaItem.Builder()
        .setUri(DriveMediaUri.build(ref))
        .setCustomCacheKey(DriveMediaUri.cacheKey(ref))
        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(title).build())
        .build()
}
