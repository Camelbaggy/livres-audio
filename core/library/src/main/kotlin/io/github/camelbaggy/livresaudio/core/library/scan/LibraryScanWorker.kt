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
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveError
import io.github.camelbaggy.livresaudio.core.library.probe.ProbeScheduling
import java.util.concurrent.TimeUnit

/** Background library walk (CLAUDE.md §5.2). Never throws: failures are typed in the [ScanReport]. */
@HiltWorker
class LibraryScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scanner: LibraryScanner
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val mode = if (inputData.getBoolean(KEY_FULL, false)) ScanMode.FULL else ScanMode.AUTO
        val report = scanner.scan(mode)
        if (report.shouldRetry() && runAttemptCount < MAX_ATTEMPTS) return Result.retry()
        // Nothing listed (no change since the last run): nothing new to probe.
        if (report.roots.isNotEmpty()) enqueueProbe()
        return Result.success()
    }

    /** Files discovered by the walk are probed (duration, tags, cover, chapters) right after it. */
    private fun enqueueProbe() {
        try {
            ProbeScheduling.enqueue(WorkManager.getInstance(applicationContext))
        } catch (_: IllegalStateException) {
            // WorkManager not initialised (tests): the next walk will schedule the probe.
        }
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
        const val KEY_FULL = "full"
        private const val MAX_ATTEMPTS = 3
    }
}

/**
 * Scheduling of [LibraryScanWorker]: every 12 h, plus on demand. Both are AUTO scans (incremental through
 * `changes.list`; each root is still listed in full at least weekly). [requestFullScanNow] forces a full walk.
 */
object LibraryScanScheduling {
    const val PERIODIC_NAME = "library-scan-periodic"
    const val NOW_NAME = "library-scan-now"
    const val FULL_NAME = "library-scan-full"
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

    /** Full control walk on demand (diagnostics). Unique, `KEEP`. */
    fun requestFullScanNow(workManager: WorkManager) {
        val request = OneTimeWorkRequestBuilder<LibraryScanWorker>()
            .setConstraints(connected)
            .setInputData(workDataOf(LibraryScanWorker.KEY_FULL to true))
            .build()
        workManager.enqueueUniqueWork(FULL_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** App opening, pull-to-refresh. `KEEP`: a request already queued or running is not replaced. */
    fun requestScanNow(workManager: WorkManager) {
        workManager.enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.KEEP, immediateRequest())
    }
}
