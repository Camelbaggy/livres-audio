package io.github.camelbaggy.livresaudio.core.library.probe

import io.github.camelbaggy.livresaudio.core.library.chapters.EmbeddedChapterExtractor
import io.github.camelbaggy.livresaudio.core.library.chapters.readFully
import io.github.camelbaggy.livresaudio.core.model.ByteRangeSource
import io.github.camelbaggy.livresaudio.core.model.ChapterExtractor
import java.io.IOException

/**
 * Reads duration, tags, embedded cover position and embedded chapters of one audio file through HTTP ranges
 * (CLAUDE.md §5.2, "Sondage"). MP4/M4A/M4B and MP3 are analysed; other formats (ogg, opus, flac, aac, wav)
 * give [ProbeFormat.OTHER] with no duration: a Media3 probe can complete them later.
 *
 * Guards: the tags and the duration cost at most [tagBudgetBytes] bytes and [MAX_READS] reads; the chapters
 * have their own budget [chapterBudgetBytes] because an M4B `moov` can weigh several MiB (sample tables). The
 * cover picture is never read here. The only exceptions that escape are [IOException] (the source failed, the
 * caller decides whether to retry) and coroutine cancellation; malformed data yields partial results.
 */
class AudioProbe(
    private val chapters: ChapterExtractor = EmbeddedChapterExtractor(),
    private val tagBudgetBytes: Long = DEFAULT_TAG_BUDGET,
    private val chapterBudgetBytes: Long = DEFAULT_CHAPTER_BUDGET
) {
    /**
     * [withChapters] false = "light" probe (phase 1 of the runner): tags, header duration and the position of
     * the cover only. The chapter extractors read the whole `moov` of an M4B (several MiB of sample tables), so
     * they are skipped; [ProbeResult.chapters] is then empty. The light probe never reads a `trak` or an `stbl`.
     */
    @Throws(IOException::class)
    suspend fun probe(source: ByteRangeSource, mimeType: String?, withChapters: Boolean = true): ProbeResult {
        val tagSource = BudgetedSource(source, tagBudgetBytes, MAX_READS)
        val format = try {
            detect(tagSource.readFully(0, DETECT_BYTES), mimeType)
        } catch (_: ProbeBudgetException) {
            ProbeFormat.OTHER
        }
        tagSource.ioFailure?.let { throw it }
        if (format == ProbeFormat.OTHER) return ProbeResult.EMPTY

        var duration: Long? = null
        var tags = AudioTags()
        try {
            when (format) {
                ProbeFormat.MP4 -> Mp4Probe.probe(tagSource).let {
                    duration = it.durationMs
                    tags = it.tags
                }

                ProbeFormat.MP3 -> Mp3Probe.probe(tagSource).let {
                    duration = it.durationMs
                    tags = it.tags
                }

                ProbeFormat.OTHER -> Unit
            }
        } catch (_: ProbeBudgetException) {
            // partial: nothing more is read for tags
        } catch (_: IndexOutOfBoundsException) {
            // defensive: corrupt data must never crash the probe
        }
        tagSource.ioFailure?.let { throw it }
        if (!withChapters) return ProbeResult(format, duration, tags, emptyList())

        val budgeted = BudgetedSource(source, chapterBudgetBytes, MAX_READS)
        val cover = tags.cover
        val chapterSource: ByteRangeSource =
            if (format == ProbeFormat.MP4 && cover != null) {
                MaskedSource(budgeted, cover.offset, cover.offset + cover.length)
            } else {
                budgeted
            }
        val found = chapters.extract(chapterSource, mimeType)
        budgeted.ioFailure?.let { throw it }
        return ProbeResult(format, duration, tags, found)
    }

    internal fun detect(head: ByteArray, mime: String?): ProbeFormat {
        if (head.size >= 3 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3") return ProbeFormat.MP3
        if (head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) in MP4_FIRST_BOXES) return ProbeFormat.MP4
        if (head.size >= 4 && Mpeg.parseHeader(head, 0) != null) return ProbeFormat.MP3
        val m = mime?.lowercase()?.substringBefore(';')?.trim() ?: return ProbeFormat.OTHER
        return when {
            m.contains("mp4") || m.contains("m4a") || m.contains("m4b") || m.contains("quicktime") -> ProbeFormat.MP4
            m == "audio/mpeg" || m == "audio/mp3" -> ProbeFormat.MP3
            else -> ProbeFormat.OTHER
        }
    }

    companion object {
        const val DEFAULT_TAG_BUDGET = 512L * 1024
        const val DEFAULT_CHAPTER_BUDGET = 16L * 1024 * 1024
        const val MAX_READS = 400
        private const val DETECT_BYTES = 12
        private val MP4_FIRST_BOXES = setOf("ftyp", "moov", "mdat", "free", "wide", "skip")
    }
}
