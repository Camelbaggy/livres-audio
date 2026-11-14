package io.github.camelbaggy.livresaudio.onboarding

import io.github.camelbaggy.livresaudio.core.data.db.WatchedRootEntity
import io.github.camelbaggy.livresaudio.core.data.repo.AccountRepository
import io.github.camelbaggy.livresaudio.core.drive.auth.AccountStore
import io.github.camelbaggy.livresaudio.core.model.AccessToken
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.core.model.AuthResult
import io.github.camelbaggy.livresaudio.core.model.AuthUi
import io.github.camelbaggy.livresaudio.core.model.ConnectedAccount
import io.github.camelbaggy.livresaudio.core.model.DriveAuthProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

object NoUi : AuthUi

fun account(email: String, role: AccountRole, profile: Boolean = false) =
    ConnectedAccount(AccountId("id-$email"), email, null, role, profile)

class FakeAccountStore(initial: List<ConnectedAccount> = emptyList()) : AccountStore {
    private val flow = MutableStateFlow(initial)
    override val accounts: Flow<List<ConnectedAccount>> = flow

    override suspend fun list() = flow.value

    override suspend fun find(id: AccountId) = flow.value.firstOrNull { it.id == id }

    override suspend fun upsert(email: String, displayName: String?, role: AccountRole): ConnectedAccount {
        val account = ConnectedAccount(AccountId("id-$email"), email, displayName, role, false)
        flow.value = flow.value.filter { it.id != account.id } + account
        return account
    }

    override suspend fun remove(id: AccountId) {
        flow.value = flow.value.filter { it.id != id }
    }
}

class FakeAuth(private val store: FakeAccountStore) : DriveAuthProvider {
    var next: AuthResult<ConnectedAccount>? = null
    var nextEmail = "a@example.org"
    var calls = 0

    override suspend fun addAccount(ui: AuthUi, role: AccountRole): AuthResult<ConnectedAccount> {
        calls++
        return next ?: AuthResult.Success(store.upsert(nextEmail, null, role))
    }

    var reconnectResult: AuthResult<ConnectedAccount>? = null
    val reconnected = mutableListOf<AccountId>()

    override suspend fun reconnectAccount(ui: AuthUi, accountId: AccountId): AuthResult<ConnectedAccount> {
        reconnected += accountId
        return reconnectResult ?: store.find(accountId)?.let { AuthResult.Success(it) }
            ?: AuthResult.Failure("compte inconnu")
    }

    override suspend fun removeAccount(accountId: AccountId) = store.remove(accountId)

    override suspend fun getAccessToken(accountId: AccountId, forceRefresh: Boolean): AuthResult<AccessToken> =
        AuthResult.Success(AccessToken("t", null))
}

class FakeAccountRepository : AccountRepository {
    val accountsFlow = MutableStateFlow<List<ConnectedAccount>>(emptyList())
    val rootsFlow = MutableStateFlow<List<WatchedRootEntity>>(emptyList())
    private var nextId = 1L
    val removedAt = mutableListOf<Pair<AccountId, Long>>()

    override fun observeAccounts(): Flow<List<ConnectedAccount>> = accountsFlow

    override suspend fun getAccounts() = accountsFlow.value

    override suspend fun upsertAccount(account: ConnectedAccount, nowMs: Long) {
        accountsFlow.value = accountsFlow.value.filter { it.id != account.id } + account
    }

    override suspend fun removeAccount(id: AccountId, nowMs: Long) {
        removedAt += id to nowMs
        accountsFlow.value = accountsFlow.value.filter { it.id != id }
        rootsFlow.value = rootsFlow.value.filter { it.accountId != id.value }
    }

    override suspend fun setProfileAccount(id: AccountId) = Unit

    override fun observeWatchedRoots(): Flow<List<WatchedRootEntity>> = rootsFlow.map { it }

    override suspend fun getWatchedRoots() = rootsFlow.value

    override suspend fun addWatchedRoot(accountId: AccountId, driveFolderId: String, name: String, nowMs: Long) {
        if (rootsFlow.value.any { it.accountId == accountId.value && it.driveFolderId == driveFolderId }) return
        rootsFlow.value = rootsFlow.value + WatchedRootEntity(nextId++, accountId.value, driveFolderId, name, nowMs)
    }

    override suspend fun removeWatchedRoot(rootId: Long, nowMs: Long) {
        rootsFlow.value = rootsFlow.value.filter { it.id != rootId }
    }
}

/** Tree: parent id -> children. The root is `root-id`. */
class FakeFolderBrowser(private val tree: Map<String, List<FolderEntry>>) : FolderBrowser {
    var failWith: FolderError? = null

    override suspend fun rootFolder(accountId: AccountId): FolderResult<FolderEntry> =
        failWith?.let { FolderResult.Failure(it) } ?: FolderResult.Success(FolderEntry("root-id", "Mon Drive"))

    override suspend fun listFolders(accountId: AccountId, parentId: String): FolderResult<List<FolderEntry>> =
        failWith?.let { FolderResult.Failure(it) } ?: FolderResult.Success(tree[parentId].orEmpty())
}
