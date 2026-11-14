package io.github.camelbaggy.livresaudio.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.camelbaggy.livresaudio.core.data.repo.AccountRepository
import io.github.camelbaggy.livresaudio.core.drive.auth.AccountStore
import io.github.camelbaggy.livresaudio.onboarding.ChosenRoot
import io.github.camelbaggy.livresaudio.onboarding.toChosenRoots
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class LibraryPlaceholderState(val roots: List<ChosenRoot> = emptyList())

/** Provisional library screen (replaced by task 1.10): just lists the chosen roots. */
@HiltViewModel
class LibraryPlaceholderViewModel @Inject constructor(store: AccountStore, repository: AccountRepository) :
    ViewModel() {
    val state: StateFlow<LibraryPlaceholderState> = combine(
        store.accounts,
        repository.observeWatchedRoots()
    ) { accounts, roots -> LibraryPlaceholderState(toChosenRoots(accounts, roots)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryPlaceholderState())
}
