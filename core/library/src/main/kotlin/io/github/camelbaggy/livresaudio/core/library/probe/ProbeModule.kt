package io.github.camelbaggy.livresaudio.core.library.probe

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.camelbaggy.livresaudio.core.data.db.LibraryDatabase
import io.github.camelbaggy.livresaudio.core.data.repo.DataIoDispatcher
import io.github.camelbaggy.livresaudio.core.model.DriveAuthProvider
import java.io.File
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher

@Module
@InstallIn(SingletonComponent::class)
object ProbeModule {
    @Provides
    @Singleton
    fun probeSources(auth: Provider<DriveAuthProvider>): ProbeSourceFactory =
        DriveProbeSourceFactory(ProbeHttp.createClient(auth = { auth.get() }))

    @Provides
    @Singleton
    fun coverStore(@ApplicationContext context: Context): CoverStore = FileCoverStore(File(context.filesDir, "covers"))

    @Provides
    fun probeStore(db: LibraryDatabase, @DataIoDispatcher dispatcher: CoroutineDispatcher): ProbeStore =
        RoomProbeStore(db, dispatcher)

    @Provides
    fun probeRunner(
        @ApplicationContext context: Context,
        store: ProbeStore,
        sources: ProbeSourceFactory,
        covers: CoverStore
    ): ProbeRunner = ProbeRunner(
        store,
        sources,
        AudioProbe(),
        covers,
        networkAvailable = { hasNetwork(context) },
        detailsAllowed = { isUnmetered(context) }
    )

    private fun isUnmetered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun hasNetwork(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
