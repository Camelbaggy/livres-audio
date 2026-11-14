package io.github.camelbaggy.livresaudio.core.library.probe

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Background probe of the audio files (CLAUDE.md §5.2). Never throws; a transient failure is retried with backoff. */
@HiltWorker
class ProbeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: ProbeRunner
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val report = runner.run()
        return if (report.retryLater && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    companion object {
        private const val MAX_ATTEMPTS = 5
    }
}

/**
 * Scheduling of [ProbeWorker]. Enqueued by the scan worker after each walk; `APPEND_OR_REPLACE` so that a walk
 * finishing while a probe runs gets its own probe afterwards, without cancelling the running one.
 */
object ProbeScheduling {
    const val UNIQUE_NAME = "library-probe"

    fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<ProbeWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .build()

    fun enqueue(workManager: WorkManager) {
        workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request())
    }
}
