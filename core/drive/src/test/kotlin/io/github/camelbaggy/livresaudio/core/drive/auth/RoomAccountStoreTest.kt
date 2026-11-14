package io.github.camelbaggy.livresaudio.core.drive.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.camelbaggy.livresaudio.core.data.db.WatchedRootEntity
import io.github.camelbaggy.livresaudio.core.data.repo.AccountRepository
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.core.model.ConnectedAccount
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/** In-memory [AccountRepository] that mimics the Room one (profile flag, removal records). */
internal class InMemoryAccountRepository(initial: List<ConnectedAccount> = emptyList()) : AccountRepository {
    val accountsFlow = MutableStateFlow(initial)
    val removed = mutableListOf<Pair<AccountId, Long>>()

    override fun observeAccounts(): Flow<List<ConnectedAccount>> = accountsFlow

    override suspend fun getAccounts() = accountsFlow.value

    override suspend fun upsertAccount(account: ConnectedAccount, nowMs: Long) {
        val updated = accountsFlow.value.filter { it.id != account.id } + account
        accountsFlow.value =
            if (account.isProfile) updated.map { it.copy(isProfile = it.id == account.id) } else updated
    }

    override suspend fun removeAccount(id: AccountId, nowMs: Long) {
        removed += id to nowMs
        accountsFlow.value = accountsFlow.value.filter { it.id != id }
    }

    override suspend fun setProfileAccount(id: AccountId) {
        accountsFlow.value = accountsFlow.value.map { it.copy(isProfile = it.id == id) }
    }

    override fun observeWatchedRoots(): Flow<List<WatchedRootEntity>> = MutableStateFlow(emptyList())

    override suspend fun getWatchedRoots(): List<WatchedRootEntity> = emptyList()

    override suspend fun addWatchedRoot(accountId: AccountId, driveFolderId: String, name: String, nowMs: Long) = Unit

    override suspend fun removeWatchedRoot(rootId: Long, nowMs: Long) = Unit
}

private val CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)

private fun acc(id: String, role: AccountRole, name: String? = null, profile: Boolean = false) =
    ConnectedAccount(AccountId(id), "$id@example.test", name, role, profile)

private fun tempFile() = File.createTempFile("accounts", ".preferences_pb").also {
    it.delete()
    it.deleteOnExit()
}

class AccountMigrationTest {
    private fun dataStore(file: File, scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })

    private suspend fun fillLegacy(store: DataStore<Preferences>) {
        val legacy = DataStoreAccountStore(
            store,
            newId = run {
                var n = 0
                { "legacy-${++n}" }
            }
        )
        legacy.upsert("commun@example.test", "Famille", AccountRole.SHARED)
        legacy.upsert("moi@example.test", null, AccountRole.PERSONAL)
    }

    @Test
    fun legacyAccountsMoveToRoomWithTheSameIds() = runTest {
        val scope = CoroutineScope(Job())
        val ds = dataStore(tempFile(), scope)
        fillLegacy(ds)
        val repo = InMemoryAccountRepository()

        AccountMigration(ds, repo, CLOCK).ensureMigrated()

        val accounts = repo.accountsFlow.value.associateBy { it.id.value }
        assertEquals(setOf("legacy-1", "legacy-2"), accounts.keys)
        assertEquals(AccountRole.SHARED, accounts.getValue("legacy-1").role)
        assertEquals("Famille", accounts.getValue("legacy-1").displayName)
        assertEquals("commun@example.test", accounts.getValue("legacy-1").email)
        assertTrue(accounts.getValue("legacy-2").isProfile)
        assertFalse(accounts.getValue("legacy-1").isProfile)
        scope.cancel()
    }

    @Test
    fun rerunChangesNothingEvenIfRoomLostAnAccountSince() = runTest {
        val scope = CoroutineScope(Job())
        val ds = dataStore(tempFile(), scope)
        fillLegacy(ds)
        val repo = InMemoryAccountRepository()
        AccountMigration(ds, repo, CLOCK).ensureMigrated()
        val after = repo.accountsFlow.value

        AccountMigration(ds, repo, CLOCK).ensureMigrated()
        assertEquals(after, repo.accountsFlow.value)

        // The user removes an account in Room: a new process must not bring it back from the old DataStore.
        repo.removeAccount(AccountId("legacy-1"), 5)
        AccountMigration(ds, repo, CLOCK).ensureMigrated()
        assertEquals(listOf("legacy-2"), repo.accountsFlow.value.map { it.id.value })
        scope.cancel()
    }

    @Test
    fun existingRoomAccountsAreNeverOverwritten() = runTest {
        val scope = CoroutineScope(Job())
        val ds = dataStore(tempFile(), scope)
        fillLegacy(ds)
        val repo = InMemoryAccountRepository(
            listOf(acc("legacy-1", AccountRole.SHARED, name = "Nom choisi dans Room", profile = false))
        )

        AccountMigration(ds, repo, CLOCK).ensureMigrated()

        val byId = repo.accountsFlow.value.associateBy { it.id.value }
        assertEquals("Nom choisi dans Room", byId.getValue("legacy-1").displayName)
        assertEquals(setOf("legacy-1", "legacy-2"), byId.keys)
        assertEquals(1, repo.accountsFlow.value.count { it.isProfile })
        scope.cancel()
    }

    @Test
    fun emptyLegacyStoreChangesNothing() = runTest {
        val scope = CoroutineScope(Job())
        val repo = InMemoryAccountRepository(listOf(acc("a", AccountRole.PERSONAL, profile = true)))
        AccountMigration(dataStore(tempFile(), scope), repo, CLOCK).ensureMigrated()
        assertEquals(listOf("a"), repo.accountsFlow.value.map { it.id.value })
        scope.cancel()
    }
}

class RoomAccountStoreTest {
    private fun fixture(scope: CoroutineScope): Pair<RoomAccountStore, InMemoryAccountRepository> {
        val repo = InMemoryAccountRepository()
        val ds = PreferenceDataStoreFactory.create(scope = scope, produceFile = { tempFile() })
        var n = 0
        val store = RoomAccountStore(repo, AccountMigration(ds, repo, CLOCK), CLOCK) { "id-${++n}" }
        return store to repo
    }

    @Test
    fun upsertKeepsIdForSameAddressAndAssignsProfile() = runTest {
        val scope = CoroutineScope(Job())
        val (store, _) = fixture(scope)

        val shared = store.upsert("Commun@Example.test", "Famille", AccountRole.SHARED)
        assertTrue(shared.isProfile)
        val personal = store.upsert("moi@example.test", null, AccountRole.PERSONAL)
        assertTrue(personal.isProfile)
        assertFalse(store.find(shared.id)!!.isProfile)

        val again = store.upsert("commun@example.test", null, AccountRole.SHARED)
        assertEquals(shared.id, again.id)
        assertEquals("Famille", again.displayName)
        assertEquals(2, store.list().size)
        assertEquals(2, store.accounts.first().size)
        scope.cancel()
    }

    @Test
    fun removeDelegatesToRepositoryAndReassignsProfile() = runTest {
        val scope = CoroutineScope(Job())
        val (store, repo) = fixture(scope)
        val shared = store.upsert("commun@example.test", null, AccountRole.SHARED)
        val personal = store.upsert("moi@example.test", null, AccountRole.PERSONAL)

        store.remove(personal.id)

        assertEquals(listOf(personal.id to 1_000L), repo.removed)
        assertNull(store.find(personal.id))
        assertNotNull(store.find(shared.id))
        assertTrue(store.find(shared.id)!!.isProfile)
        scope.cancel()
    }
}
