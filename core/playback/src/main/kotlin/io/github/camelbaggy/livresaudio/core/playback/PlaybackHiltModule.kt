package io.github.camelbaggy.livresaudio.core.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.camelbaggy.livresaudio.core.model.DriveAuthProvider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
@UnstableApi
object PlaybackHiltModule {
    /** One `SimpleCache` per directory: it must be a singleton. */
    @Provides
    @Singleton
    fun playbackCache(@ApplicationContext context: Context): PlaybackCache = PlaybackCache(context)

    @Provides
    @Singleton
    fun networkMonitor(@ApplicationContext context: Context): NetworkMonitor = NetworkMonitor(context)

    @Provides
    @Singleton
    fun playbackEngine(
        @ApplicationContext context: Context,
        auth: Lazy<DriveAuthProvider>,
        cache: PlaybackCache
    ): PlaybackEngine = PlaybackEngine(context, auth = { auth.get() }, playbackCache = cache)

    @Provides
    @PlaybackMainDispatcher
    fun mainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate

    @Provides
    @PlaybackWriteDispatcher
    fun writeDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
