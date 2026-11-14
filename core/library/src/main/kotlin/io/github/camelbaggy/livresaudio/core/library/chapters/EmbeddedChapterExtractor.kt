package io.github.camelbaggy.livresaudio.core.library.chapters

import io.github.camelbaggy.livresaudio.core.model.ByteRangeSource
import io.github.camelbaggy.livresaudio.core.model.ChapterExtractor
import io.github.camelbaggy.livresaudio.core.model.EmbeddedChapter
import kotlin.coroutines.cancellation.CancellationException

/** Choisit le parseur par signature (`ID3`, `ftyp`), à défaut d'après le type MIME. Ne lève jamais (hors annulation). */
class EmbeddedChapterExtractor(
    private val mp4: Mp4ChapterParser = Mp4ChapterParser(),
    private val id3: Id3ChapterParser = Id3ChapterParser()
) : ChapterExtractor {
    override suspend fun extract(source: ByteRangeSource, mimeType: String?): List<EmbeddedChapter> = try {
        when (detect(source.readFully(0, 12), mimeType)) {
            Format.MP4 -> mp4.parse(source)
            Format.ID3 -> id3.parse(source)
            null -> emptyList()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    private enum class Format { MP4, ID3 }

    private fun detect(head: ByteArray, mime: String?): Format? {
        if (head.size >= 3 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3") return Format.ID3
        if (head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) in MP4_FIRST_BOXES) return Format.MP4
        val m = mime?.lowercase()?.substringBefore(';')?.trim() ?: return null
        return when {
            m.contains("mp4") || m.contains("m4a") || m.contains("m4b") || m.contains("quicktime") -> Format.MP4
            m == "audio/mpeg" || m == "audio/mp3" -> Format.ID3
            else -> null
        }
    }

    private companion object {
        val MP4_FIRST_BOXES = setOf("ftyp", "moov", "mdat", "free", "wide", "skip")
    }
}
