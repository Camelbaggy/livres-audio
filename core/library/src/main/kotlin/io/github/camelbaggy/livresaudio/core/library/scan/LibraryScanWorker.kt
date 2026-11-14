package io.github.camelbaggy.livresaudio.core.library.scan

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveError
import java.util.concurrent.TimeUnit

/** Background library walk (CLAUDE.md §5.2). Never throws: failures are typed in the [ScanReport]. */
@HiltWorker
class LibraryScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scanner: LibraryScanner
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val report = scanner.scan()
        return if (report.shouldRetry() && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    /** Transient failures are retried with backoff; auth or storage problems are not fixed by retrying. */
    private fun ScanReport.shouldRetry(): Boolean = roots.any {
        val outcome = it.outcome
        outcome is RootScanOutcome.Failed && outcome.failure.isTransient()
    }

    private fun ScanFailure.isTransient() = this is ScanFailure.Drive && when (error) {
        DriveError.NetworkUnavailable, is DriveError.RateLimited, is DriveError.Server -> true
        else -> false
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
    }
}

/** Scheduling of [LibraryScanWorker]: every 12 h, plus on demand. Both are unique and use `KEEP`. */
object LibraryScanScheduling {
    const val PERIODIC_NAME = "library-scan-periodic"
    const val NOW_NAME = "library-scan-now"
    const val PERIOD_HOURS = 12L

    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun periodicRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<LibraryScanWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

    fun immediateRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<LibraryScanWorker>()
        .setConstraints(connected)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .build()

    /** Call once at start-up: `KEEP` leaves an existing schedule alone. */
    fun schedulePeriodic(workManager: WorkManager) {
        workManager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, periodicRequest())
    }

    /** App opening, pull-to-refresh. `KEEP`: a request already queued or running is not replaced. */
    fun requestScanNow(workManager: WorkManager) {
        workManager.enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.KEEP, immediateRequest())
    }
}
