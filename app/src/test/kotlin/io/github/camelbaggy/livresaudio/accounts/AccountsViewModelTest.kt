package io.github.camelbaggy.livresaudio.accounts

import io.github.camelbaggy.livresaudio.core.model.AccessToken
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.core.model.AuthResult
import io.github.camelbaggy.livresaudio.core.model.AuthUi
import io.github.camelbaggy.livresaudio.core.model.ConnectedAccount
import io.github.camelbaggy.livresaudio.core.model.DriveAuthProvider
import io.github.camelbaggy.livresaudio.onboarding.ConnectError
import io.github.camelbaggy.livresaudio.onboarding.FakeAccountRepository
import io.github.camelbaggy.livresaudio.onboarding.FakeFolderBrowser
import io.github.camelbaggy.livresaudio.onboarding.FolderEntry
import io.github.camelbaggy.livresaudio.onboarding.NoUi
import io.github.camelbaggy.livresaudio.onboarding.account
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/** Auth provider whose account list is the repository (what the real Room-backed store does). */
private class RepoAuth(private val repo: FakeAccountRepository) : DriveAuthProvider {
    var reconnectResult: AuthResult<ConnectedAccount>? = null
    val reconnected = mutableListOf<AccountId>()
    val removed = mutableListOf<AccountId>()

    override suspend fun addAccount(ui: AuthUi, role: AccountRole): AuthResult<ConnectedAccount> = AuthResult.Cancelled

    override suspend fun reconnectAccount(ui: AuthUi, accountId: AccountId): AuthResult<ConnectedAccount> {
        reconnected += accountId
        return reconnectResult ?: AuthResult.Success(repo.getAccounts().first { it.id == accountId })
    }

    override suspend fun removeAccount(accountId: AccountId) {
        removed += accountId
        repo.removeAccount(accountId, 1_000)
    }

    override suspend fun getAccessToken(accountId: AccountId, forceRefresh: Boolean) =
        AuthResult.Success(AccessToken("t", null))
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountsViewModelTest {
    private lateinit var repo: FakeAccountRepository
    private lateinit var auth: RepoAuth
    private val shared = account("commun@example.org", AccountRole.SHARED)
    private val personal = account("moi@example.org", AccountRole.PERSONAL, profile = true)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repo = FakeAccountRepository()
        auth = RepoAuth(repo)
        repo.accountsFlow.value = listOf(shared, personal)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(tree: Map<String, List<FolderEntry>> = emptyMap()) = AccountsViewModel(
        auth,
        repo,
        FakeFolderBrowser(tree),
        Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)
    )

    @Test
    fun listsAccountsWithTheirFolders() {
        val vm = vm()
        vm.startAddFolder(shared.id)
        vm.chooseCurrentFolder()
        val items = vm.state.value.accounts
        assertEquals(listOf(shared.id, personal.id), items.map { it.account.id })
        assertEquals(listOf("Mon Drive"), items.first { it.account.id == shared.id }.roots.map { it.name })
        assertTrue(items.first { it.account.id == personal.id }.roots.isEmpty())
    }

    @Test
    fun sharedAccountCanBeReconnectedWhileSharedIsAlreadyConnected() {
        val vm = vm()
        vm.reconnect(shared.id, NoUi)
        assertEquals(listOf(shared.id), auth.reconnected)
        assertEquals(AccountsMessage.Reconnected("commun@example.org"), vm.state.value.message)
        assertNull(vm.state.value.reconnecting)
    }

    @Test
    fun reconnectFailuresAreReportedWithoutTouchingTheAccount() {
        val vm = vm()
        auth.reconnectResult = AuthResult.NeedsUserAction
        vm.reconnect(personal.id, NoUi)
        val message = assertIs<AccountsMessage.ReconnectFailed>(vm.state.value.message)
        assertEquals(ConnectError.NeedsUserAction, message.error)
        vm.dismissMessage()
        assertNull(vm.state.value.message)
        assertEquals(2, repo.accountsFlow.value.size)
    }

    @Test
    fun removalNeedsExplicitConfirmation() {
        val vm = vm()
        repo.rootsFlow.value = emptyList()
        vm.requestRemoval(personal.id)
        assertEquals(personal.id, vm.state.value.pendingRemoval?.account?.id)
        assertTrue(auth.removed.isEmpty())
        assertEquals(2, repo.accountsFlow.value.size)

        vm.cancelRemoval()
        assertNull(vm.state.value.pendingRemoval)
        assertTrue(auth.removed.isEmpty())

        vm.requestRemoval(personal.id)
        vm.confirmRemoval()
        assertEquals(listOf(personal.id), auth.removed)
        assertEquals(listOf(shared.id), repo.accountsFlow.value.map { it.id })
        assertEquals(listOf(personal.id to 1_000L), repo.removedAt)
        assertNull(vm.state.value.pendingRemoval)
    }

    @Test
    fun confirmWithoutRequestDoesNothing() {
        val vm = vm()
        vm.confirmRemoval()
        assertTrue(auth.removed.isEmpty())
    }

    @Test
    fun chooserAddsAndRemovesFolders() {
        val tree = mapOf("root-id" to listOf(FolderEntry("f1", "Livres")))
        val vm = vm(tree)
        vm.startAddFolder(personal.id)
        assertTrue(vm.state.value.browsing)
        vm.openFolder(FolderEntry("f1", "Livres"))
        vm.chooseCurrentFolder()
        val root = vm.state.value.accounts.first { it.account.id == personal.id }.roots.single()
        assertEquals("Livres", root.name)
        assertTrue(vm.state.value.currentFolderChosen)

        vm.removeRoot(root.id)
        assertTrue(vm.state.value.accounts.first { it.account.id == personal.id }.roots.isEmpty())

        vm.backInChooser() // one level up
        assertTrue(vm.state.value.browsing)
        vm.backInChooser() // at the root: closes the chooser
        assertTrue(!vm.state.value.browsing)
    }
}
