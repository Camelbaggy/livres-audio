package io.github.camelbaggy.livresaudio.onboarding

import io.github.camelbaggy.livresaudio.core.model.AccountRole
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class AccountLinkerTest {
    private val clock = Clock.fixed(Instant.ofEpochMilli(5), ZoneOffset.UTC)

    @Test
    fun copiesStoreAccountsWithSameIdsAndNeverRemovesOthers() = runTest {
        val a = account("a@example.org", AccountRole.PERSONAL, profile = true)
        val b = account("b@example.org", AccountRole.SHARED)
        val stale = account("old@example.org", AccountRole.PERSONAL)
        val store = FakeAccountStore(listOf(a, b))
        val repo = FakeAccountRepository()
        repo.upsertAccount(stale, 0)
        AccountLinker(store, repo, clock).syncAll()
        // An account unknown to the store is kept: removing it would drop its watched roots.
        assertEquals(setOf(a.id, b.id, stale.id), repo.getAccounts().map { it.id }.toSet())
        // Idempotent.
        AccountLinker(store, repo, clock).syncAll()
        assertEquals(3, repo.getAccounts().size)
    }
}
