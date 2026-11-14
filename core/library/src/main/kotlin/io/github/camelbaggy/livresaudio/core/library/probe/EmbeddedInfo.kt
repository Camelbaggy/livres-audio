package io.github.camelbaggy.livresaudio.core.library.probe

import io.github.camelbaggy.livresaudio.core.data.db.BookEntity
import io.github.camelbaggy.livresaudio.core.data.db.BookField
import io.github.camelbaggy.livresaudio.core.data.identity.FieldMeta
import io.github.camelbaggy.livresaudio.core.data.identity.MergedField
import io.github.camelbaggy.livresaudio.core.data.identity.mergeField
import io.github.camelbaggy.livresaudio.core.model.FieldSource

/** Book-level information deduced from the tags of its files (provenance `EMBEDDED`, CLAUDE.md §4.3). */
data class EmbeddedFields(
    val title: String? = null,
    val author: String? = null,
    val narrator: String? = null,
    val seriesName: String? = null,
    val seriesIndex: Double? = null,
    val year: Int? = null,
    val description: String? = null
) {
    val isEmpty: Boolean
        get() = title == null && author == null && narrator == null && seriesName == null &&
            seriesIndex == null && year == null && description == null
}

/** Pure rules turning file tags into book fields. */
object EmbeddedInfo {
    /**
     * [orderedTags]: tags of the files of the book, in reading order (the first file with a value wins for
     * each field). Multi-file book: the title is the album (a track title is a chapter, not the book).
     * Single-file book: the title tag, else the album. Author = album artist, else artist. Narrator = explicit
     * narrator, else composer (unless it repeats the author).
     */
    fun fieldsFor(orderedTags: List<AudioTags>, multiFile: Boolean): EmbeddedFields {
        fun first(pick: (AudioTags) -> String?): String? = orderedTags.firstNotNullOfOrNull { clean(pick(it)) }
        val title = if (multiFile) {
            first { it.album }
        } else {
            first { it.title } ?: first { it.album }
        }
        val author = first { it.albumArtist } ?: first { it.artist }
        val narrator = (first { it.narrator } ?: first { it.composer })
            ?.takeUnless { author != null && it.equals(author, ignoreCase = true) }
        val withSeries = orderedTags.firstOrNull { clean(it.series) != null }
        return EmbeddedFields(
            title = title,
            author = author,
            narrator = narrator,
            seriesName = withSeries?.series?.let(::clean),
            seriesIndex = withSeries?.seriesIndex?.takeIf { it > 0 },
            year = orderedTags.firstNotNullOfOrNull { it.year },
            description = first { it.description }
        )
    }

    private val PLACEHOLDERS = setOf(
        "unknown", "unknown album", "unknown artist", "<unknown>", "inconnu", "artiste inconnu", "album inconnu",
        "various artists", "audiobook", "audio book", "livre audio", "track", "n/a"
    )

    private fun clean(value: String?): String? {
        val v = value?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        return v.takeIf { it.isNotEmpty() && it.lowercase() !in PLACEHOLDERS }
    }
}

/**
 * Applies embedded fields (and cover) through the single merge rule of CLAUDE.md §4.3: a `USER` value is never
 * touched, a null incoming value never erases anything, `FOLDER` and `ONLINE` values are replaced.
 */
internal fun BookEntity.withEmbedded(fields: EmbeddedFields, coverUri: String?, nowMs: Long): BookEntity {
    val meta = FieldMeta(FieldSource.EMBEDDED, nowMs)
    val sources = fieldSources.toMutableMap()

    fun <T> merge(field: String, current: T?, incoming: T?): T? {
        val result = mergeField(MergedField(current, sources[field]), incoming, meta)
        result.meta?.let { sources[field] = it }
        return result.value
    }
    return copy(
        title = merge(BookField.TITLE, title, fields.title),
        author = merge(BookField.AUTHOR, author, fields.author),
        narrator = merge(BookField.NARRATOR, narrator, fields.narrator),
        seriesName = merge(BookField.SERIES_NAME, seriesName, fields.seriesName),
        seriesIndex = merge(BookField.SERIES_INDEX, seriesIndex, fields.seriesIndex),
        description = merge(BookField.DESCRIPTION, description, fields.description),
        year = merge(BookField.YEAR, year, fields.year),
        coverUri = merge(BookField.COVER_URI, this.coverUri, coverUri),
        fieldSources = sources
    )
}
