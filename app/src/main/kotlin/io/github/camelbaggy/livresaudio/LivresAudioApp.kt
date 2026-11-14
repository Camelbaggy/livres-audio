package io.github.camelbaggy.livresaudio

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import io.github.camelbaggy.livresaudio.core.library.scan.LibraryScanScheduling
import javax.inject.Inject

@HiltAndroidApp
class LivresAudioApp :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        val workManager = WorkManager.getInstance(this)
        // Parcours de contrôle toutes les 12 h, et un parcours à chaque ouverture (CLAUDE.md §5.2).
        LibraryScanScheduling.schedulePeriodic(workManager)
        LibraryScanScheduling.requestScanNow(workManager)
    }
}
