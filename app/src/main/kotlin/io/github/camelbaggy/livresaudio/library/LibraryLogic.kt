package io.github.camelbaggy.livresaudio.library

import io.github.camelbaggy.livresaudio.core.data.db.BookWithProgress
import io.github.camelbaggy.livresaudio.core.model.BookOrigin
import io.github.camelbaggy.livresaudio.core.model.BookState
import io.github.camelbaggy.livresaudio.core.model.ListeningStatus
import java.text.Normalizer

enum class LibrarySort { TITLE, AUTHOR, ADDED, LAST_PLAYED }

data class LibraryItem(
    val bookId: String,
    val title: String?,
    val author: String?,
    val narrator: String?,
    val seriesName: String?,
    val coverUri: String?,
    val origin: BookOrigin,
    /** 0..1, null when the book has not been started or its duration is unknown. */
    val progress: Float?,
    val status: ListeningStatus?,
    val addedAt: Long,
    val lastPlayedAt: Long?
)

data class LibraryUiState(
    val loaded: Boolean = false,
    /** Books after search and sort. */
    val items: List<LibraryItem> = emptyList(),
    /** Last book listened to, null while searching or when none is in progress. */
    val resume: LibraryItem? = null,
    val query: String = "",
    val sort: LibrarySort = LibrarySort.TITLE,
    val refreshing: Boolean = false,
    /** True when the library holds no visible book at all (before any search). */
    val libraryEmpty: Boolean = false
)

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/** Lower case without accents, for search and alphabetical sort. Pure. */
fun normalizeForSearch(text: String): String =
    COMBINING_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase().trim()

/** Every word of [query] must appear in title, author, narrator or series (accents and case ignored). Pure. */
fun matchesQuery(item: LibraryItem, query: String): Boolean {
    val words = normalizeForSearch(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val haystack = listOfNotNull(item.title, item.author, item.narrator, item.seriesName)
        .joinToString(" ") { normalizeForSearch(it) }
    return words.all { it in haystack }
}

fun BookWithProgress.toItem(): LibraryItem {
    val progress = progress
    val total = book.totalDurationMs
    val fraction = when {
        progress == null -> null
        progress.status == ListeningStatus.FINISHED -> 1f
        progress.status == ListeningStatus.NOT_STARTED -> null
        total != null && total > 0 -> (progress.bookPositionMs.toFloat() / total).coerceIn(0f, 1f)
        else -> null
    }
    return LibraryItem(
        bookId = book.bookId,
        title = book.title,
        author = book.author,
        narrator = book.narrator,
        seriesName = book.seriesName,
        coverUri = book.coverUri,
        origin = book.origin,
        progress = fraction,
        status = progress?.status,
        addedAt = book.addedAt,
        lastPlayedAt = progress?.lastPlayedAt
    )
}

/** Sorts [items]; never-played books come last with LAST_PLAYED, books without author last with AUTHOR. Pure. */
fun sortItems(items: List<LibraryItem>, sort: LibrarySort): List<LibraryItem> {
    val byTitle = compareBy<LibraryItem> { normalizeForSearch(it.title.orEmpty()) }
    return when (sort) {
        LibrarySort.TITLE -> items.sortedWith(byTitle)

        LibrarySort.AUTHOR -> items.sortedWith(
            compareBy<LibraryItem> { it.author.isNullOrBlank() }
                .thenBy { normalizeForSearch(it.author.orEmpty()) }
                .then(byTitle)
        )

        LibrarySort.ADDED -> items.sortedWith(compareByDescending<LibraryItem> { it.addedAt }.then(byTitle))

        LibrarySort.LAST_PLAYED -> items.sortedWith(
            compareByDescending<LibraryItem> { it.lastPlayedAt ?: Long.MIN_VALUE }.then(byTitle)
        )
    }
}

/** The "Reprendre" book: most recently played among those in progress. Pure. */
fun resumeCandidate(items: List<LibraryItem>): LibraryItem? = items
    .filter { it.status == ListeningStatus.IN_PROGRESS && it.lastPlayedAt != null }
    .maxByOrNull { it.lastPlayedAt ?: 0L }

/** Everything the library screen shows. MISSING books are hidden even if the repository returned them. Pure. */
fun buildLibraryState(rows: List<BookWithProgress>, query: String, sort: LibrarySort, refreshing: Boolean) =
    rows.filter { it.book.state == BookState.PRESENT }.map { it.toItem() }.let { all ->
        LibraryUiState(
            loaded = true,
            items = sortItems(all.filter { matchesQuery(it, query) }, sort),
            resume = if (query.isBlank()) resumeCandidate(all) else null,
            query = query,
            sort = sort,
            refreshing = refreshing,
            libraryEmpty = all.isEmpty()
        )
    }
