package io.github.camelbaggy.livresaudio.core.library.probe

import io.github.camelbaggy.livresaudio.core.model.EmbeddedChapter

enum class ProbeFormat { MP4, MP3, OTHER }

/** Position of an embedded picture inside the audio file: the image itself is NOT read by the probe. */
data class EmbeddedCover(val offset: Long, val length: Long, val mimeType: String?)

/** Tags read from one audio file. Every field is null when absent or unreadable. */
data class AudioTags(
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    /** Composer: audiobooks often store the narrator here. */
    val composer: String? = null,
    /** Explicit narrator (`TXXX:NARRATOR`, `----:com.apple.iTunes:NARRATOR`). */
    val narrator: String? = null,
    val series: String? = null,
    val seriesIndex: Double? = null,
    val track: Int? = null,
    val trackTotal: Int? = null,
    val disc: Int? = null,
    val discTotal: Int? = null,
    val year: Int? = null,
    val description: String? = null,
    val cover: EmbeddedCover? = null
)

/**
 * What a probe found. [durationMs] is null when unknown (unsupported format, no usable header).
 * [format] `OTHER` (ogg, opus, flac, aac, wav...) is accepted without duration: a later Media3 probe may fill it.
 */
data class ProbeResult(
    val format: ProbeFormat,
    val durationMs: Long?,
    val tags: AudioTags,
    val chapters: List<EmbeddedChapter>
) {
    companion object {
        val EMPTY = ProbeResult(ProbeFormat.OTHER, null, AudioTags(), emptyList())
    }
}
