package io.github.camelbaggy.livresaudio.core.library.probe

import android.content.Context
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
    fun probeRunner(store: ProbeStore, sources: ProbeSourceFactory, covers: CoverStore): ProbeRunner =
        ProbeRunner(store, sources, AudioProbe(), covers)
}
