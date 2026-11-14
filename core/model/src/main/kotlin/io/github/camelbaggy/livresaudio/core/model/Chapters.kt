package io.github.camelbaggy.livresaudio.core.model

/** A chapter marker embedded in one audio file (M4B `chpl`/QuickTime chapter track, MP3 `CHAP`). */
data class EmbeddedChapter(
    val title: String?,
    val startMs: Long,
    /** Null when the file does not give it (then: start of the next chapter or end of file). */
    val endMs: Long?
)

/**
 * Random-access reader over an audio file, local or remote (HTTP ranges on Drive).
 * Implementations must tolerate short reads at end of file.
 */
interface ByteRangeSource {
    /** Total size in bytes, or null if unknown. */
    suspend fun size(): Long?

    /** Reads up to [length] bytes starting at [offset]; returns fewer at end of file. */
    suspend fun read(offset: Long, length: Int): ByteArray
}

/** Extracts embedded chapters by reading only the needed byte ranges (CLAUDE.md §5.2, essai E3). */
interface ChapterExtractor {
    /** Empty list when the file has no chapters or the format is not recognised. */
    suspend fun extract(source: ByteRangeSource, mimeType: String?): List<EmbeddedChapter>
}
