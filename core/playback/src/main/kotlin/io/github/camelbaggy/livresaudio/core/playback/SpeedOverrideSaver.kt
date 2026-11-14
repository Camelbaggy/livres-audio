package io.github.camelbaggy.livresaudio.core.playback

import io.github.camelbaggy.livresaudio.core.data.repo.ProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Persists a book's speed override. `ProgressRepository.setSpeedOverride` does nothing when the book has no
 * progress row yet (a book never played), so the saver waits for the row, which the [PositionKeeper] creates at
 * its first save, and then writes the override. Only the latest request per book counts. Call from one thread (the player's). Never throws.
 */
class SpeedOverrideSaver(
    private val repository: ProgressRepository,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long
) {
    private val jobs = HashMap<String, Job>()

    /** [speed] null clears the override. Safe to call repeatedly: the previous pending request for the same book is dropped. */
    fun save(bookId: String, speed: Float?) {
        jobs.remove(bookId)?.cancel()
        jobs[bookId] = scope.launch(dispatcher) {
            try {
                if (repository.get(bookId) == null) repository.observe(bookId).filterNotNull().first()
                repository.setSpeedOverride(bookId, speed, clock())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Not critical (the speed stays applied in the player); the next change writes again.
            }
        }
    }

    fun cancel() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }
}
