package io.github.camelbaggy.livresaudio.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.camelbaggy.livresaudio.core.library.scan.LibraryScanScheduling
import io.github.camelbaggy.livresaudio.core.playback.ActiveBook
import io.github.camelbaggy.livresaudio.core.playback.ActiveBookHolder
import io.github.camelbaggy.livresaudio.library.ScanRequester
import io.github.camelbaggy.livresaudio.player.ActiveBookSource
import io.github.camelbaggy.livresaudio.player.MediaControllerHolder
import io.github.camelbaggy.livresaudio.player.PlaybackCommands
import io.github.camelbaggy.livresaudio.player.PlaybackGateway
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackUiBindingsModule {
    @Binds
    abstract fun gateway(impl: MediaControllerHolder): PlaybackGateway

    @Binds
    abstract fun commands(impl: MediaControllerHolder): PlaybackCommands
}

@Module
@InstallIn(SingletonComponent::class)
object PlaybackUiProvidesModule {
    @Provides
    @Singleton
    fun activeBookSource(holder: ActiveBookHolder): ActiveBookSource = object : ActiveBookSource {
        override val active: StateFlow<ActiveBook?> = holder.active
    }

    @Provides
    @Singleton
    fun scanRequester(@ApplicationContext context: Context): ScanRequester =
        ScanRequester { LibraryScanScheduling.requestScanNow(WorkManager.getInstance(context)) }
}
