package io.github.camelbaggy.livresaudio.core.data.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
        Room.databaseBuilder(context, LibraryDatabase::class.java, LibraryDatabase.NAME).build()

    @Provides
    @DataIoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
