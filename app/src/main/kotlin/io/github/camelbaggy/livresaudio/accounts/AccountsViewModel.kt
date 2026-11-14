package io.github.camelbaggy.livresaudio.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.camelbaggy.livresaudio.core.data.repo.AccountRepository
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.AuthResult
import io.github.camelbaggy.livresaudio.core.model.AuthUi
import io.github.camelbaggy.livresaudio.core.model.ConnectedAccount
import io.github.camelbaggy.livresaudio.core.model.DriveAuthProvider
import io.github.camelbaggy.livresaudio.onboarding.ChosenRoot
import io.github.camelbaggy.livresaudio.onboarding.ConnectError
import io.github.camelbaggy.livresaudio.onboarding.Crumb
import io.github.camelbaggy.livresaudio.onboarding.FolderBrowser
import io.github.camelbaggy.livresaudio.onboarding.FolderBrowserState
import io.github.camelbaggy.livresaudio.onboarding.FolderEntry
import io.github.camelbaggy.livresaudio.onboarding.FolderResult
import io.github.camelbaggy.livresaudio.onboarding.toChosenRoots
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An account with the folders it watches. */
data class AccountItem(val account: ConnectedAccount, val roots: List<ChosenRoot>)

/** Result of the last reconnection, shown once. */
sealed interface AccountsMessage {
    data class Reconnected(val email: String) : AccountsMessage

    data class ReconnectFailed(val email: String, val error: ConnectError) : AccountsMessage
}

data class AccountsUiState(
    val accounts: List<AccountItem> = emptyList(),
    /** Set while the folder chooser is open for [FolderBrowserState.account]. */
    val browser: FolderBrowserState = FolderBrowserState(),
    /** Account for which the removal confirmation is shown. */
    val pendingRemoval: AccountItem? = null,
    /** Account whose consent is being redone. */
    val reconnecting: AccountId? = null,
    val message: AccountsMessage? = null
) {
    val browsing: Boolean get() = browser.account != null

    val currentFolderChosen: Boolean
        get() {
            val account = browser.account ?: return false
            val folder = browser.current ?: return false
            return accounts.any { item ->
                item.account.id == account.id && item.roots.any { it.driveFolderId == folder.id }
            }
        }
}

private data class Local(
    val browser: FolderBrowserState = FolderBrowserState(),
    val pendingRemoval: AccountId? = null,
    val reconnecting: AccountId? = null,
    val message: AccountsMessage? = null
)

/** Settings screen "Comptes et dossiers" (CLAUDE.md task 1.2, §5.5 point 5). Room is the only source. */
@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val auth: DriveAuthProvider,
    private val repository: AccountRepository,
    private val folders: FolderBrowser,
    private val clock: Clock
) : ViewModel() {
    private val local = MutableStateFlow(Local())
    private var loadJob: Job? = null

    val state: StateFlow<AccountsUiState> = combine(
        local,
        repository.observeAccounts(),
        repository.observeWatchedRoots()
    ) { l, accounts, roots ->
        val chosen = toChosenRoots(accounts, roots)
        val items = accounts.map { a -> AccountItem(a, chosen.filter { it.accountId == a.id }) }
        AccountsUiState(
            accounts = items,
            browser = l.browser,
            pendingRemoval = items.firstOrNull { it.account.id == l.pendingRemoval },
            reconnecting = l.reconnecting,
            message = l.message
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AccountsUiState())

    /** Redoes the consent of an already connected account, whatever its role (the shared one included). */
    fun reconnect(accountId: AccountId, ui: AuthUi) {
        if (local.value.reconnecting != null) return
        val account = state.value.accounts.firstOrNull { it.account.id == accountId }?.account ?: return
        local.update { it.copy(reconnecting = accountId, message = null) }
        viewModelScope.launch {
            val message = when (val result = auth.reconnectAccount(ui, accountId)) {
                is AuthResult.Success -> AccountsMessage.Reconnected(account.email)

                AuthResult.Cancelled ->
                    AccountsMessage.ReconnectFailed(account.email, ConnectError.Cancelled)

                AuthResult.NeedsUserAction ->
                    AccountsMessage.ReconnectFailed(account.email, ConnectError.NeedsUserAction)

                AuthResult.NetworkUnavailable ->
                    AccountsMessage.ReconnectFailed(account.email, ConnectError.NetworkUnavailable)

                is AuthResult.Failure ->
                    AccountsMessage.ReconnectFailed(account.email, ConnectError.Failure(result.message))
            }
            local.update { it.copy(reconnecting = null, message = message) }
        }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    /** First step of the removal: only asks for the confirmation, nothing is changed. */
    fun requestRemoval(accountId: AccountId) = local.update { it.copy(pendingRemoval = accountId) }

    fun cancelRemoval() = local.update { it.copy(pendingRemoval = null) }

    /**
     * Confirmed removal: the account is forgotten (and its Google token cleared), its watched roots stop being
     * walked and its books become MISSING (hidden, kept 90 days). Listening positions and bookmarks are kept.
     */
    fun confirmRemoval() {
        val id = local.value.pendingRemoval ?: return
        local.update { it.copy(pendingRemoval = null) }
        viewModelScope.launch { auth.removeAccount(id) }
    }

    fun removeRoot(rootId: Long) {
        viewModelScope.launch { repository.removeWatchedRoot(rootId, clock.millis()) }
    }

    // --- Folder chooser -------------------------------------------------------------------------------------

    fun startAddFolder(accountId: AccountId) {
        val account = state.value.accounts.firstOrNull { it.account.id == accountId }?.account ?: return
        local.update { it.copy(browser = FolderBrowserState(account = account, loading = true), message = null) }
        viewModelScope.launch {
            when (val root = folders.rootFolder(account.id)) {
                is FolderResult.Failure -> local.update {
                    it.copy(browser = it.browser.copy(loading = false, error = root.error))
                }

                is FolderResult.Success -> {
                    local.update {
                        it.copy(browser = it.browser.copy(path = listOf(Crumb(root.value.id, root.value.name))))
                    }
                    load()
                }
            }
        }
    }

    fun openFolder(entry: FolderEntry) {
        local.update { it.copy(browser = it.browser.copy(path = it.browser.path + Crumb(entry.id, entry.name))) }
        load()
    }

    fun goToCrumb(index: Int) {
        val path = local.value.browser.path
        if (index !in path.indices || index == path.lastIndex) return
        local.update { it.copy(browser = it.browser.copy(path = path.take(index + 1))) }
        load()
    }

    /** Goes up one level; false when already at the root. */
    fun up(): Boolean {
        val size = local.value.browser.path.size
        if (size <= 1) return false
        goToCrumb(size - 2)
        return true
    }

    fun retry() {
        val b = local.value.browser
        val account = b.account ?: return
        if (b.path.isEmpty()) startAddFolder(account.id) else load()
    }

    fun chooseCurrentFolder() {
        val b = local.value.browser
        val account = b.account ?: return
        val folder = b.current ?: return
        if (state.value.currentFolderChosen) return
        viewModelScope.launch { repository.addWatchedRoot(account.id, folder.id, folder.name, clock.millis()) }
    }

    /** System back in the chooser: one level up, then close. */
    fun backInChooser() {
        if (!up()) closeChooser()
    }

    fun closeChooser() {
        loadJob?.cancel()
        local.update { it.copy(browser = FolderBrowserState()) }
    }

    private fun load() {
        val b = local.value.browser
        val account = b.account ?: return
        val folder = b.current ?: return
        loadJob?.cancel()
        local.update { it.copy(browser = it.browser.copy(loading = true, error = null)) }
        loadJob = viewModelScope.launch {
            when (val result = folders.listFolders(account.id, folder.id)) {
                is FolderResult.Success -> local.update {
                    it.copy(browser = it.browser.copy(folders = result.value, loading = false))
                }

                is FolderResult.Failure -> local.update {
                    it.copy(browser = it.browser.copy(folders = emptyList(), loading = false, error = result.error))
                }
            }
        }
    }
}
