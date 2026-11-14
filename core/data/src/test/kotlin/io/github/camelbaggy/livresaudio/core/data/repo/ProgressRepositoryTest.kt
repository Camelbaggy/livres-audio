package io.github.camelbaggy.livresaudio.core.data.repo

import io.github.camelbaggy.livresaudio.core.model.ListeningStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class ProgressRepositoryTest : RepoTestBase() {
    private val repo by lazy { RoomProgressRepository(db, dispatcher) }

    private suspend fun save(pos: Long, status: ListeningStatus, now: Long, allowReset: Boolean = false) =
        repo.savePosition("b1", "f1", "m1", 0, pos, pos, status, now, allowReset)

    @Test
    fun savesAndMarksDirty() = runTest {
        assertEquals(SaveResult.Saved, save(5_000, ListeningStatus.IN_PROGRESS, 100))
        val p = assertNotNull(repo.get("b1"))
        assertEquals(5_000L, p.positionMs)
        assertEquals(100L, p.lastPlayedAt)
        assertTrue(p.dirty)
        assertEquals(5_000L, repo.observe("b1").first()?.bookPositionMs)
    }

    @Test
    fun zeroPositionOverNonZeroIsRefused() = runTest {
        save(5_000, ListeningStatus.IN_PROGRESS, 100)
        assertEquals(SaveResult.RefusedReset, save(0, ListeningStatus.IN_PROGRESS, 200))
        assertEquals(SaveResult.RefusedReset, save(9_000, ListeningStatus.NOT_STARTED, 200))
        val p = assertNotNull(repo.get("b1"))
        assertEquals(5_000L, p.positionMs)
        assertEquals(100L, p.updatedAt)
    }

    @Test
    fun explicitResetIsAllowed() = runTest {
        save(5_000, ListeningStatus.IN_PROGRESS, 100)
        assertEquals(SaveResult.Saved, save(0, ListeningStatus.NOT_STARTED, 200, allowReset = true))
        assertEquals(0L, repo.get("b1")?.positionMs)
    }

    @Test
    fun zeroOnFirstSaveIsAccepted() = runTest {
        assertEquals(SaveResult.Saved, save(0, ListeningStatus.NOT_STARTED, 100))
    }

    @Test
    fun speedOverrideSurvivesPositionSaves() = runTest {
        save(1_000, ListeningStatus.IN_PROGRESS, 100)
        repo.setSpeedOverride("b1", 1.5f, 150)
        save(2_000, ListeningStatus.IN_PROGRESS, 200)
        assertEquals(1.5f, repo.get("b1")?.speed)
    }
}
