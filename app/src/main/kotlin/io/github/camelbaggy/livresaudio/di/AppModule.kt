package io.github.camelbaggy.livresaudio.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.camelbaggy.livresaudio.core.drive.rest.AuthInterceptor
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveRestClient
import io.github.camelbaggy.livresaudio.core.drive.rest.RetryInterceptor
import io.github.camelbaggy.livresaudio.core.drive.rest.TokenAuthenticator
import io.github.camelbaggy.livresaudio.core.model.DriveAuthProvider
import io.github.camelbaggy.livresaudio.onboarding.DriveFolderBrowser
import io.github.camelbaggy.livresaudio.onboarding.FolderBrowser
import io.github.camelbaggy.livresaudio.ui.DriveImageFetcherFactory
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import javax.inject.Singleton
import okhttp3.OkHttpClient

private const val IMAGE_TIMEOUT_S = 20L

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun clock(): Clock = Clock.systemUTC()

    /** Authenticated client for Drive cover images (token per account, renewal on 401, backoff on 5xx). */
    @Provides
    @Singleton
    fun driveImageFetcherFactory(auth: Provider<DriveAuthProvider>): DriveImageFetcherFactory {
        val client = OkHttpClient.Builder()
            .connectTimeout(IMAGE_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(IMAGE_TIMEOUT_S, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor())
            .addInterceptor(AuthInterceptor { auth.get() })
            .authenticator(TokenAuthenticator { auth.get() })
            .build()
        return DriveImageFetcherFactory(client)
    }

    @Provides
    @Singleton
    fun folderBrowser(drive: DriveRestClient): FolderBrowser = DriveFolderBrowser(drive)
}
