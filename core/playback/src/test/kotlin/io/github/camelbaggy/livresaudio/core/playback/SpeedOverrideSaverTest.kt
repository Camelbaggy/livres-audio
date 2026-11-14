package io.github.camelbaggy.livresaudio.core.playback

import io.github.camelbaggy.livresaudio.core.model.ListeningStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SpeedOverrideSaverTest {
    @Test
    fun `writes at once when the book has progress`() = runTest {
        val repo = FakeProgressRepository(progress())
        val saver = SpeedOverrideSaver(repo, backgroundScope, StandardTestDispatcher(testScheduler)) { 5L }
        saver.save(BOOK_ID, 1.5f)
        runCurrent()
        assertEquals(1.5f, repo.state.value?.speed)
        saver.save(BOOK_ID, null)
        runCurrent()
        assertEquals(null, repo.state.value?.speed)
    }

    @Test
    fun `waits for the first progress row of a book never played`() = runTest {
        val repo = FakeProgressRepository(null)
        val saver = SpeedOverrideSaver(repo, backgroundScope, StandardTestDispatcher(testScheduler)) { 5L }
        saver.save(BOOK_ID, 1.25f)
        runCurrent()
        assertEquals(emptyList<Pair<String, Float?>>(), repo.speedCalls)
        // The keeper's first save creates the row.
        createRow(repo)
        runCurrent()
        assertEquals(listOf<Pair<String, Float?>>(BOOK_ID to 1.25f), repo.speedCalls)
        assertEquals(1.25f, repo.state.value?.speed)
    }

    @Test
    fun `only the latest request of a book counts`() = runTest {
        val repo = FakeProgressRepository(null)
        val saver = SpeedOverrideSaver(repo, backgroundScope, StandardTestDispatcher(testScheduler)) { 5L }
        saver.save(BOOK_ID, 1.25f)
        saver.save(BOOK_ID, 1.75f)
        runCurrent()
        createRow(repo)
        runCurrent()
        assertEquals(listOf<Pair<String, Float?>>(BOOK_ID to 1.75f), repo.speedCalls)
    }

    private suspend fun createRow(repo: FakeProgressRepository) = repo.savePosition(
        BOOK_ID,
        "f0",
        "md5-0",
        0,
        1_000,
        1_000,
        ListeningStatus.IN_PROGRESS,
        1,
        false
    )
}
