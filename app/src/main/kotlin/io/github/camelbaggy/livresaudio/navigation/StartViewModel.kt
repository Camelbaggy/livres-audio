package io.github.camelbaggy.livresaudio.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.camelbaggy.livresaudio.core.data.repo.AccountRepository
import io.github.camelbaggy.livresaudio.core.drive.auth.AccountStore
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class AppDestination { Onboarding, Library }

/** Start routing: library as soon as one account and one watched root exist, onboarding otherwise. Pure. */
fun resolveStartDestination(accountCount: Int, rootCount: Int): AppDestination =
    if (accountCount > 0 && rootCount > 0) AppDestination.Library else AppDestination.Onboarding

@HiltViewModel
class StartViewModel @Inject constructor(store: AccountStore, repository: AccountRepository) : ViewModel() {
    private val _destination = MutableStateFlow<AppDestination?>(null)

    /** Null while resolving; computed once, never changes afterwards. */
    val destination: StateFlow<AppDestination?> = _destination

    init {
        viewModelScope.launch {
            _destination.value = resolveStartDestination(store.list().size, repository.getWatchedRoots().size)
        }
    }
}
