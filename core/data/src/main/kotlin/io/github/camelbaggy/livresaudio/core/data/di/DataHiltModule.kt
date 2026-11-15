package io.github.camelbaggy.livresaudio.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.camelbaggy.livresaudio.core.data.db.LIBRARY_MIGRATIONS
import io.github.camelbaggy.livresaudio.core.data.db.LibraryDatabase
import io.github.camelbaggy.livresaudio.core.data.repo.AccountRepository
import io.github.camelbaggy.livresaudio.core.data.repo.BookmarkRepository
import io.github.camelbaggy.livresaudio.core.data.repo.DataIoDispatcher
import io.github.camelbaggy.livresaudio.core.data.repo.LibraryRepository
import io.github.camelbaggy.livresaudio.core.data.repo.ProgressRepository
import io.github.camelbaggy.livresaudio.core.data.repo.RoomAccountRepository
import io.github.camelbaggy.livresaudio.core.data.repo.RoomBookmarkRepository
import io.github.camelbaggy.livresaudio.core.data.repo.RoomLibraryRepository
import io.github.camelbaggy.livresaudio.core.data.repo.RoomProgressRepository
import io.github.camelbaggy.livresaudio.core.data.settings.DataStoreListeningSettingsStore
import io.github.camelbaggy.livresaudio.core.data.settings.ListeningSettingsDataStore
import io.github.camelbaggy.livresaudio.core.model.ListeningSettingsStore
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindingsModule {
    @Binds
    abstract fun accounts(impl: RoomAccountRepository): AccountRepository

    @Binds
    abstract fun library(impl: RoomLibraryRepository): LibraryRepository

    @Binds
    abstract fun progress(impl: RoomProgressRepository): ProgressRepository

    @Binds
    abstract fun bookmarks(impl: RoomBookmarkRepository): BookmarkRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DataProvidesModule {
    /** No `fallbackToDestructiveMigration`: a missing migration must fail loudly, never erase positions. */
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): LibraryDatabase =
        Room.databaseBuilder(context, LibraryDatabase::class.java, LibraryDatabase.NAME)
            .addMigrations(*LIBRARY_MIGRATIONS)
            .build()

    @Provides
    @DataIoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /** Own, qualified DataStore: `core:drive` provides an unqualified `DataStore<Preferences>` for the accounts. */
    @Provides
    @Singleton
    @ListeningSettingsDataStore
    fun listeningSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(DataStoreListeningSettingsStore.FILE_NAME) }
        )

    @Provides
    @Singleton
    fun listeningSettingsStore(@ListeningSettingsDataStore dataStore: DataStore<Preferences>): ListeningSettingsStore =
        DataStoreListeningSettingsStore(dataStore)
}
