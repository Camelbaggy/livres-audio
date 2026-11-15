package io.github.camelbaggy.livresaudio.core.library.scan

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.camelbaggy.livresaudio.core.data.repo.AccountRepository
import io.github.camelbaggy.livresaudio.core.data.repo.BookRootLookup
import io.github.camelbaggy.livresaudio.core.data.repo.DataIoDispatcher
import io.github.camelbaggy.livresaudio.core.data.repo.LibraryRepository
import io.github.camelbaggy.livresaudio.core.data.repo.SyncStateRepository
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveRestClient
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher

@Module
@InstallIn(SingletonComponent::class)
object LibraryScanModule {
    @Provides
    @Singleton
    fun treeLister(client: DriveRestClient): TreeLister = DriveTreeLister(client)

    @Provides
    @Singleton
    fun changeFeed(client: DriveRestClient): ChangeFeed = DriveChangeFeed(client)

    /** Singleton: the mutex serialising walks lives in the instance. */
    @Provides
    @Singleton
    fun libraryScanner(
        accounts: AccountRepository,
        library: LibraryRepository,
        lister: TreeLister,
        changes: ChangeFeed,
        syncState: SyncStateRepository,
        bookRoots: BookRootLookup,
        @DataIoDispatcher dispatcher: CoroutineDispatcher
    ): LibraryScanner = LibraryScanner(
        accounts,
        library,
        lister,
        changes,
        syncState,
        ChangedRootsResolver(changes, bookRoots),
        dispatcher
    )
}
