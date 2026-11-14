package io.github.camelbaggy.livresaudio.core.model

/** Provenance of a book information field, by increasing priority (CLAUDE.md §4.3). */
enum class FieldSource(val priority: Int) {
    FOLDER(0),
    ONLINE(1),
    EMBEDDED(2),
    USER(3)
}

/** A value with its provenance and last update time (UTC milliseconds). */
data class Sourced<out T>(val value: T, val source: FieldSource, val updatedAt: Long)

enum class BookState { PRESENT, MISSING }

enum class MatchState { UNMATCHED, AUTO, NEEDS_REVIEW, USER }

enum class ListeningStatus { NOT_STARTED, IN_PROGRESS, FINISHED }

/** Information deduced from folder and file names (provenance [FieldSource.FOLDER]). */
data class ParsedName(
    val title: String? = null,
    val author: String? = null,
    val narrator: String? = null,
    val seriesName: String? = null,
    /** Decimal accepted: 0, 3.5, 5.1 (CLAUDE.md §6, docs/formes-de-noms.md). */
    val seriesIndex: Double? = null
)
