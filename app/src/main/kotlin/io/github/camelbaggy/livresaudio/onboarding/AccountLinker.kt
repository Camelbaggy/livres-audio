package io.github.camelbaggy.livresaudio.onboarding

import io.github.camelbaggy.livresaudio.core.data.repo.AccountRepository
import io.github.camelbaggy.livresaudio.core.drive.auth.AccountStore
import java.time.Clock
import javax.inject.Inject

/**
 * Keeps the Room `accounts` table aligned with [AccountStore] (DataStore, the source of truth for accounts at
 * this stage because it knows the addresses used for tokens). Needed so that `watched_roots` can reference an
 * account. DEBT: to remove when accounts are unified in a single store.
 */
class AccountLinker @Inject constructor(
    private val store: AccountStore,
    private val repository: AccountRepository,
    private val clock: Clock
) {
    /**
     * Writes every store account into Room (same [io.github.camelbaggy.livresaudio.core.model.AccountId]).
     * Additive only: an account missing from the store (cleared or unreadable DataStore) is NEVER removed here,
     * because removing it would cascade to its watched roots. Removal is an explicit user action.
     */
    suspend fun syncAll() {
        val now = clock.millis()
        store.list().forEach { repository.upsertAccount(it, now) }
    }
}
