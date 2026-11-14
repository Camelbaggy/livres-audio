package io.github.camelbaggy.livresaudio.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.camelbaggy.livresaudio.core.data.db.WatchedRootEntity
import io.github.camelbaggy.livresaudio.core.data.repo.AccountRepository
import io.github.camelbaggy.livresaudio.core.drive.auth.AccountStore
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.core.model.AuthResult
import io.github.camelbaggy.livresaudio.core.model.AuthUi
import io.github.camelbaggy.livresaudio.core.model.ConnectedAccount
import io.github.camelbaggy.livresaudio.core.model.DriveAuthProvider
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

enum class OnboardingStep { Welcome, ChooseRole, Connecting, ChooseFolders, Summary }

sealed interface ConnectError {
    /** Only one shared (family) account is allowed. */
    data object SharedAlreadyConnected : ConnectError

    data object Cancelled : ConnectError

    data object NeedsUserAction : ConnectError

    data object NetworkUnavailable : ConnectError

    data class Failure(val message: String) : ConnectError
}

data class Crumb(val id: String, val name: String)

/** State of the Drive folder chooser for one account. */
data class FolderBrowserState(
    val account: ConnectedAccount? = null,
    val path: List<Crumb> = emptyList(),
    val folders: List<FolderEntry> = emptyList(),
    val loading: Boolean = false,
    val error: FolderError? = null
) {
    val current: Crumb? get() = path.lastOrNull()
}

/** A watched root as shown in lists. */
data class ChosenRoot(
    val id: Long,
    val accountId: AccountId,
    val accountEmail: String?,
    val driveFolderId: String,
    val name: String
)

/** Pure join of watched roots with the accounts that own them. */
fun toChosenRoots(accounts: List<ConnectedAccount>, roots: List<WatchedRootEntity>): List<ChosenRoot> {
    val byId = accounts.associateBy { it.id.value }
    return roots.map {
        ChosenRoot(it.id, AccountId(it.accountId), byId[it.accountId]?.email, it.driveFolderId, it.name)
    }
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val accounts: List<ConnectedAccount> = emptyList(),
    val roots: List<ChosenRoot> = emptyList(),
    /** False once a shared account is connected: the "shared Drive" role is then refused. */
    val sharedRoleAvailable: Boolean = true,
    val error: ConnectError? = null,
    val browser: FolderBrowserState = FolderBrowserState(),
    /** Set when the user finishes the onboarding; the screen then navigates to the library. */
    val finished: Boolean = false
) {
    val currentFolderChosen: Boolean
        get() {
            val account = browser.account ?: return false
            val folder = browser.current ?: return false
            return roots.any { it.accountId == account.id && it.driveFolderId == folder.id }
        }

    val canFinish: Boolean get() = roots.isNotEmpty()
}

private data class Local(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val error: ConnectError? = null,
    val browser: FolderBrowserState = FolderBrowserState(),
    val finished: Boolean = false
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val auth: DriveAuthProvider,
    private val store: AccountStore,
    private val repository: AccountRepository,
    private val folders: FolderBrowser,
    private val clock: Clock
) : ViewModel() {
    private val local = MutableStateFlow(Local())
    private var loadJob: Job? = null

    val state: StateFlow<OnboardingUiState> = combine(
        local,
        store.accounts,
        repository.observeWatchedRoots()
    ) { l, accounts, roots ->
        OnboardingUiState(
            step = l.step,
            accounts = accounts,
            roots = toChosenRoots(accounts, roots),
            sharedRoleAvailable = accounts.none { it.role == AccountRole.SHARED },
            error = l.error,
            browser = l.browser,
            finished = l.finished
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, OnboardingUiState())

    init {
        viewModelScope.launch {
            // Coming back to the onboarding with accounts already connected: start at the summary.
            if (store.list().isNotEmpty() && local.value.step == OnboardingStep.Welcome) {
                local.update { it.copy(step = OnboardingStep.Summary) }
            }
        }
    }

    // Extension point (lot 4, CLAUDE.md §4.5/§5.5 point 1): once the shared account is connected, read
    // `.livres-audio/config.json` from its Drive; if present, apply its watched roots and skip the folder step.

    fun start() = local.update { it.copy(step = OnboardingStep.ChooseRole, error = null) }

    /** The user chose a role: start the Google connection. Refuses a second shared account. */
    fun chooseRole(role: AccountRole, ui: AuthUi) {
        if (local.value.step == OnboardingStep.Connecting) return
        viewModelScope.launch {
            if (role == AccountRole.SHARED && store.list().any { it.role == AccountRole.SHARED }) {
                local.update { it.copy(error = ConnectError.SharedAlreadyConnected) }
                return@launch
            }
            local.update { it.copy(step = OnboardingStep.Connecting, error = null) }
            when (val result = auth.addAccount(ui, role)) {
                is AuthResult.Success -> {
                    startBrowsing(result.value)
                }

                AuthResult.Cancelled -> connectFailed(ConnectError.Cancelled)

                AuthResult.NeedsUserAction -> connectFailed(ConnectError.NeedsUserAction)

                AuthResult.NetworkUnavailable -> connectFailed(ConnectError.NetworkUnavailable)

                is AuthResult.Failure -> connectFailed(ConnectError.Failure(result.message))
            }
        }
    }

    private fun connectFailed(error: ConnectError) =
        local.update { it.copy(step = OnboardingStep.ChooseRole, error = error) }

    private suspend fun startBrowsing(account: ConnectedAccount) {
        local.update {
            it.copy(
                step = OnboardingStep.ChooseFolders,
                error = null,
                browser = FolderBrowserState(account = account, loading = true)
            )
        }
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

    /** Summary: choose more folders for an already connected account. */
    fun browseAccount(accountId: AccountId) {
        viewModelScope.launch {
            val account = store.find(accountId) ?: return@launch
            startBrowsing(account)
        }
    }

    /** Opens a sub-folder of the current folder. */
    fun openFolder(entry: FolderEntry) {
        local.update { it.copy(browser = it.browser.copy(path = it.browser.path + Crumb(entry.id, entry.name))) }
        load()
    }

    /** Jumps to the breadcrumb at [index] (0 = My Drive). */
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
        if (b.path.isEmpty()) {
            viewModelScope.launch { startBrowsing(account) }
        } else {
            load()
        }
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

    /** "Choose this folder": records the current folder as a watched root (no-op if already chosen). */
    fun chooseCurrentFolder() {
        val b = local.value.browser
        val account = b.account ?: return
        val folder = b.current ?: return
        if (state.value.currentFolderChosen) return
        viewModelScope.launch { repository.addWatchedRoot(account.id, folder.id, folder.name, clock.millis()) }
    }

    fun removeRoot(rootId: Long) {
        viewModelScope.launch { repository.removeWatchedRoot(rootId, clock.millis()) }
    }

    fun showSummary() = local.update { it.copy(step = OnboardingStep.Summary, error = null) }

    /** "Add another account": back to the role choice. */
    fun addAnotherAccount() = local.update { it.copy(step = OnboardingStep.ChooseRole, error = null) }

    fun finish() {
        if (!state.value.canFinish) return
        local.update { it.copy(finished = true) }
    }

    /** System back; false when there is nothing to go back to (the caller leaves the screen). */
    fun back(): Boolean {
        val s = state.value
        return when (s.step) {
            OnboardingStep.Welcome, OnboardingStep.Connecting -> false

            OnboardingStep.ChooseRole -> {
                local.update {
                    it.copy(
                        step = if (s.accounts.isEmpty()) OnboardingStep.Welcome else OnboardingStep.Summary,
                        error = null
                    )
                }
                true
            }

            OnboardingStep.ChooseFolders -> {
                if (!up()) showSummary()
                true
            }

            OnboardingStep.Summary -> false
        }
    }
}
