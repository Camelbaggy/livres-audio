package io.github.camelbaggy.livresaudio

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import io.github.camelbaggy.livresaudio.core.drive.auth.AccountStore
import io.github.camelbaggy.livresaudio.core.library.scan.LibraryScanScheduling
import io.github.camelbaggy.livresaudio.ui.DriveImageFetcherFactory
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class LivresAudioApp :
    Application(),
    Configuration.Provider,
    SingletonImageLoader.Factory {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var driveImageFetcherFactory: DriveImageFetcherFactory

    @Inject
    lateinit var accountStore: AccountStore

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /** Coil: adds the `drive-image` scheme (folder covers, downloaded with the Drive account, disk-cached). */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).components { add(driveImageFetcherFactory) }.build()

    override fun onCreate() {
        super.onCreate()
        // Carries accounts of the legacy DataStore over to Room as early as possible (idempotent, CLAUDE.md §13).
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { accountStore.list() }
        val workManager = WorkManager.getInstance(this)
        // Parcours de contrôle toutes les 12 h, et un parcours à chaque ouverture (CLAUDE.md §5.2).
        LibraryScanScheduling.schedulePeriodic(workManager)
        LibraryScanScheduling.requestScanNow(workManager)
    }
}
