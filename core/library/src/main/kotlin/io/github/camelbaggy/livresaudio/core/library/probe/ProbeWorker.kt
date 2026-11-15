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
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Background probe of the audio files (CLAUDE.md §5.2). Never throws.
 *
 * It works at most [WORK_BUDGET_MS] (WorkManager stops a worker after about 10 minutes), then, if work remains
 * and this run made progress, it queues the next run itself, right away. A run that made no progress at all
 * (offline, revoked authorisation...) returns `retry`: WorkManager's exponential backoff spaces the attempts
 * (up to 5 hours) and never gives up, so the library is always completed eventually, without spinning.
 * If the system stops the worker, everything already committed stays (one commit per book or file).
 *
 * With [KEY_BOOK_ID], probes that one book only (opened by the user, see [ProbeScheduling.prioritize]).
 */
@HiltWorker
class ProbeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: ProbeRunner
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID)
        val report = runner.run(WORK_BUDGET_MS, bookId)
        return when {
            !report.hasRemainingWork -> Result.success()

            // The global run covers the book again later: an on-demand run does not insist.
            bookId != null -> if (runAttemptCount < ON_DEMAND_ATTEMPTS) Result.retry() else Result.success()

            report.progressed -> {
                ProbeScheduling.enqueue(WorkManager.getInstance(applicationContext))
                Result.success()
            }

            else -> Result.retry()
        }
    }

    companion object {
        const val KEY_BOOK_ID = "bookId"
        const val WORK_BUDGET_MS = 8L * 60 * 1000
        private const val ON_DEMAND_ATTEMPTS = 3
    }
}

/** Scheduling of [ProbeWorker]. */
object ProbeScheduling {
    const val UNIQUE_NAME = "library-probe"
    private const val BOOK_NAME_PREFIX = "library-probe-book-"

    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun builder() = OneTimeWorkRequestBuilder<ProbeWorker>()
        .setConstraints(connected)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)

    fun request(): OneTimeWorkRequest = builder().build()

    /**
     * Enqueued by the scan worker after each walk and by [ProbeWorker] itself when time ran out. `APPEND_OR_REPLACE`:
     * the new run starts after the running one (it never cancels it, so a worker can queue its own successor),
     * and replaces a chain that failed or was cancelled instead of being stuck behind it.
     */
    fun enqueue(workManager: WorkManager) {
        workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request())
    }

    /**
     * The user opened this book: probe it (both phases) without waiting for the global run. One work per book,
     * `KEEP` so that reopening the sheet does not pile up requests. Running it next to the global run is harmless:
     * commits are idempotent (at worst a few requests are made twice).
     */
    fun prioritize(workManager: WorkManager, bookId: String) {
        val request = builder().setInputData(workDataOf(ProbeWorker.KEY_BOOK_ID to bookId)).build()
        workManager.enqueueUniqueWork(BOOK_NAME_PREFIX + bookId, ExistingWorkPolicy.KEEP, request)
    }
}
