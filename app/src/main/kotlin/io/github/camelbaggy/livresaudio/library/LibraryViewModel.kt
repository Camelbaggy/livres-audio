package io.github.camelbaggy.livresaudio.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.camelbaggy.livresaudio.core.data.repo.LibraryRepository
import io.github.camelbaggy.livresaudio.player.PlaybackGateway
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Asks for a library walk now (pull-to-refresh). Implemented over `LibraryScanScheduling.requestScanNow`. */
fun interface ScanRequester {
    fun requestScanNow()
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    repository: LibraryRepository,
    private val gateway: PlaybackGateway,
    private val scan: ScanRequester
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(LibrarySort.TITLE)
    private val refreshing = MutableStateFlow(false)

    val state: StateFlow<LibraryUiState> = combine(
        repository.observeLibrary(),
        query,
        sort,
        refreshing
    ) { rows, q, s, r -> buildLibraryState(rows, q, s, r) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            LibraryUiState(query = "", sort = LibrarySort.TITLE)
        )

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onSortChange(value: LibrarySort) {
        sort.value = value
    }

    /** The walk runs in WorkManager; the indicator only acknowledges the gesture. */
    fun refresh() {
        scan.requestScanNow()
        viewModelScope.launch {
            refreshing.value = true
            delay(REFRESH_FEEDBACK_MS)
            refreshing.value = false
        }
    }

    fun resume(bookId: String) = gateway.playBook(bookId)

    private companion object {
        const val REFRESH_FEEDBACK_MS = 1_500L
    }
}
