package io.github.camelbaggy.livresaudio.core.drive.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import io.github.camelbaggy.livresaudio.core.data.repo.AccountRepository
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.core.model.ConnectedAccount
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One-time carry-over of the accounts of the legacy DataStore (trial E1) to Room (CLAUDE.md task 1.2).
 *
 * The legacy [AccountId]s are kept as they are: watched roots and books reference them. Idempotent, additive:
 * an account already in Room (same id, or same address) is never overwritten, nothing is ever removed, and the
 * legacy DataStore is left untouched except for the "migrated" flag.
 */
class AccountMigration internal constructor(
    private val legacyDataStore: DataStore<Preferences>,
    private val repository: AccountRepository,
    private val clock: Clock
) {
    private val mutex = Mutex()
    private var done = false
    private val migratedKey = booleanPreferencesKey("migrated_to_room")

    /** Runs the migration at most once per process (and once for all, thanks to the persisted flag). */
    suspend fun ensureMigrated() {
        if (done) return
        mutex.withLock {
            if (done) return
            if (legacyDataStore.data.first()[migratedKey] != true) migrate()
            done = true
        }
    }

    private suspend fun migrate() {
        val legacy = DataStoreAccountStore(legacyDataStore).list()
        val inRoom = repository.getAccounts()
        val now = clock.millis()
        legacy.forEachIndexed { index, account ->
            val known = inRoom.any { it.id == account.id || it.email.equals(account.email, ignoreCase = true) }
            if (!known) repository.upsertAccount(account.copy(isProfile = false), now + index)
        }
        normalizeProfile(repository)
        legacyDataStore.edit { it[migratedKey] = true }
    }
}

/** Makes Room's profile flag agree with [assignProfile]; touches nothing when already consistent. */
internal suspend fun normalizeProfile(repository: AccountRepository) {
    val accounts = repository.getAccounts()
    val target = assignProfile(accounts).firstOrNull { it.isProfile } ?: return
    val current = accounts.filter { it.isProfile }.map { it.id }
    if (current != listOf(target.id)) repository.setProfileAccount(target.id)
}

/** [AccountStore] over the Room `accounts` table, the single source of truth for accounts. */
class RoomAccountStore internal constructor(
    private val repository: AccountRepository,
    private val migration: AccountMigration,
    private val clock: Clock,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) : AccountStore {
    override val accounts: Flow<List<ConnectedAccount>> = flow {
        migration.ensureMigrated()
        emitAll(repository.observeAccounts())
    }

    override suspend fun list(): List<ConnectedAccount> {
        migration.ensureMigrated()
        return repository.getAccounts()
    }

    override suspend fun find(id: AccountId): ConnectedAccount? = list().firstOrNull { it.id == id }

    override suspend fun upsert(email: String, displayName: String?, role: AccountRole): ConnectedAccount {
        val existing = list().firstOrNull { it.email.equals(email, ignoreCase = true) }
        val id = existing?.id ?: AccountId(newId())
        repository.upsertAccount(
            ConnectedAccount(id, email, displayName ?: existing?.displayName, role, existing?.isProfile ?: false),
            clock.millis()
        )
        normalizeProfile(repository)
        return requireNotNull(find(id))
    }

    override suspend fun remove(id: AccountId) {
        migration.ensureMigrated()
        repository.removeAccount(id, clock.millis())
        normalizeProfile(repository)
    }
}
