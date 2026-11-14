package io.github.camelbaggy.livresaudio.book

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.camelbaggy.livresaudio.core.data.db.BookWithFiles
import io.github.camelbaggy.livresaudio.core.data.db.ProgressEntity
import io.github.camelbaggy.livresaudio.core.data.repo.LibraryRepository
import io.github.camelbaggy.livresaudio.core.data.repo.ProgressRepository
import io.github.camelbaggy.livresaudio.core.model.BookOrigin
import io.github.camelbaggy.livresaudio.core.model.ListeningStatus
import io.github.camelbaggy.livresaudio.player.ActiveBookSource
import io.github.camelbaggy.livresaudio.player.ChapterRow
import io.github.camelbaggy.livresaudio.player.PlaybackGateway
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BookDetailState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val bookId: String = "",
    val title: String? = null,
    val author: String? = null,
    val narrator: String? = null,
    val seriesName: String? = null,
    val seriesIndex: Double? = null,
    val description: String? = null,
    val coverUri: String? = null,
    val origin: BookOrigin = BookOrigin.PERSONAL,
    val totalDurationMs: Long? = null,
    val chapters: List<ChapterRow> = emptyList(),
    val status: ListeningStatus? = null,
    /** 0..1; null when unknown. */
    val progress: Float? = null,
    val bookPositionMs: Long = 0,
    /** True when there is a saved position to resume ("Reprendre" instead of "Lire"). */
    val canResume: Boolean = false,
    /** The service currently has this book loaded. */
    val loadedInPlayer: Boolean = false,
    val confirmReset: Boolean = false
)

/** Chapters of the sheet: the embedded ones, else one per file. Pure. */
fun chapterRows(book: BookWithFiles): List<ChapterRow> = if (book.chapters.isNotEmpty()) {
    book.chapters.sortedBy { it.indexInBook }.mapIndexed { i, c ->
        ChapterRow(i, c.title, c.endMs?.let { (it - c.startMs).coerceAtLeast(0) })
    }
} else {
    book.files.sortedBy { it.sortIndex }.mapIndexed { i, f -> ChapterRow(i, f.name, f.durationMs) }
}

fun buildDetailState(
    book: BookWithFiles?,
    progress: ProgressEntity?,
    loadedBookId: String?,
    confirmReset: Boolean
): BookDetailState {
    if (book == null) return BookDetailState(loading = false, notFound = true)
    val total = book.book.totalDurationMs
    val fraction = when {
        progress == null || progress.status == ListeningStatus.NOT_STARTED -> null
        progress.status == ListeningStatus.FINISHED -> 1f
        total != null && total > 0 -> (progress.bookPositionMs.toFloat() / total).coerceIn(0f, 1f)
        else -> null
    }
    return BookDetailState(
        loading = false,
        bookId = book.book.bookId,
        title = book.book.title,
        author = book.book.author,
        narrator = book.book.narrator,
        seriesName = book.book.seriesName,
        seriesIndex = book.book.seriesIndex,
        description = book.book.description,
        coverUri = book.book.coverUri,
        origin = book.book.origin,
        totalDurationMs = total,
        chapters = chapterRows(book),
        status = progress?.status,
        progress = fraction,
        bookPositionMs = progress?.bookPositionMs ?: 0L,
        canResume = progress != null &&
            progress.status == ListeningStatus.IN_PROGRESS &&
            (progress.positionMs > 0 || progress.bookPositionMs > 0),
        loadedInPlayer = loadedBookId == book.book.bookId,
        confirmReset = confirmReset
    )
}

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val library: LibraryRepository,
    private val progressRepository: ProgressRepository,
    private val gateway: PlaybackGateway,
    activeBook: ActiveBookSource,
    private val clock: Clock
) : ViewModel() {
    private val bookId: String = checkNotNull(savedState[ARG_BOOK_ID]) { "bookId manquant" }
    private val confirmReset = MutableStateFlow(false)

    val state: StateFlow<BookDetailState> = combine(
        library.observeBook(bookId),
        progressRepository.observe(bookId),
        activeBook.active,
        confirmReset
    ) { book, progress, active, confirm -> buildDetailState(book, progress, active?.book?.bookId, confirm) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookDetailState())

    fun play() = gateway.playBook(bookId)

    fun requestReset() {
        confirmReset.value = true
    }

    fun dismissReset() {
        confirmReset.value = false
    }

    /** Confirmed reset: through the service when the book is loaded, else directly in the position repository. */
    fun confirmReset() {
        confirmReset.value = false
        if (state.value.loadedInPlayer) {
            gateway.resetPosition()
        } else {
            viewModelScope.launch {
                val first = library.getBook(bookId)?.files?.minByOrNull { it.sortIndex } ?: return@launch
                progressRepository.savePosition(
                    bookId = bookId,
                    driveFileId = first.driveFileId,
                    fileMd5 = first.md5,
                    fileIndex = 0,
                    positionMs = 0,
                    bookPositionMs = 0,
                    status = ListeningStatus.NOT_STARTED,
                    nowMs = clock.millis(),
                    allowReset = true
                )
            }
        }
    }

    /** Keeps the saved position, only flips the status (a loaded book is paused first so it cannot overwrite it). */
    fun markFinished() {
        if (state.value.loadedInPlayer) gateway.pause()
        viewModelScope.launch {
            val book = library.getBook(bookId) ?: return@launch
            val existing = progressRepository.get(bookId)
            val file = book.files.sortedBy { it.sortIndex }.let { files ->
                files.getOrNull(existing?.fileIndex ?: 0) ?: files.firstOrNull()
            } ?: return@launch
            progressRepository.savePosition(
                bookId = bookId,
                driveFileId = file.driveFileId,
                fileMd5 = file.md5,
                fileIndex = existing?.fileIndex ?: 0,
                positionMs = existing?.positionMs ?: 0,
                bookPositionMs = existing?.bookPositionMs ?: 0,
                status = ListeningStatus.FINISHED,
                nowMs = clock.millis()
            )
        }
    }

    companion object {
        const val ARG_BOOK_ID = "bookId"
    }
}
