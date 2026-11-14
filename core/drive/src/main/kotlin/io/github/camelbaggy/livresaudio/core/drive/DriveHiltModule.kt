package io.github.camelbaggy.livresaudio.core.drive

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.camelbaggy.livresaudio.core.drive.auth.AboutFetcher
import io.github.camelbaggy.livresaudio.core.drive.auth.AccountStore
import io.github.camelbaggy.livresaudio.core.drive.auth.AuthorizationGateway
import io.github.camelbaggy.livresaudio.core.drive.auth.DataStoreAccountStore
import io.github.camelbaggy.livresaudio.core.drive.auth.GmsAuthorizationGateway
import io.github.camelbaggy.livresaudio.core.drive.auth.GoogleDriveAuthProvider
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveHttp
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveRestClient
import io.github.camelbaggy.livresaudio.core.model.DriveAuthProvider
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DriveHiltModule {
    @Provides
    @Singleton
    fun accountDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("accounts") })

    @Provides
    @Singleton
    fun accountStore(dataStore: DataStore<Preferences>): AccountStore = DataStoreAccountStore(dataStore)

    @Provides
    @Singleton
    fun authorizationGateway(@ApplicationContext context: Context): AuthorizationGateway =
        GmsAuthorizationGateway(context)

    /** [Provider]/[Lazy] break the cycle auth provider -> about -> REST client -> interceptors -> auth provider. */
    @Provides
    @Singleton
    fun driveRestClient(auth: Provider<DriveAuthProvider>): DriveRestClient =
        DriveHttp.createClient(auth = { auth.get() })

    @Provides
    @Singleton
    fun driveAuthProvider(
        store: AccountStore,
        gateway: AuthorizationGateway,
        client: Lazy<DriveRestClient>
    ): DriveAuthProvider = GoogleDriveAuthProvider(store, gateway) { token -> client.get().aboutWithToken(token) }
}
