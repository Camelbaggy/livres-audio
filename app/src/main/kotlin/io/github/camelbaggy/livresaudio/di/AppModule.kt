package io.github.camelbaggy.livresaudio.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveRestClient
import io.github.camelbaggy.livresaudio.onboarding.DriveFolderBrowser
import io.github.camelbaggy.livresaudio.onboarding.FolderBrowser
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun clock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    fun folderBrowser(drive: DriveRestClient): FolderBrowser = DriveFolderBrowser(drive)
}
