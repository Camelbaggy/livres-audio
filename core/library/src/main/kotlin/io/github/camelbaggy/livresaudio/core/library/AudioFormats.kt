package io.github.camelbaggy.livresaudio.core.library

import io.github.camelbaggy.livresaudio.core.model.DriveItem

/** Recognition of audio files and cover images in a Drive listing (CLAUDE.md §5.2, rule 5). */
object AudioFormats {
    enum class Kind { SUPPORTED_AUDIO, UNSUPPORTED_AUDIO, IMAGE, OTHER }

    val supportedExtensions = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav")

    /** Listed but flagged "format non pris en charge". */
    val unsupportedExtensions = setOf("wma")
    val imageExtensions = setOf("jpg", "jpeg", "png", "webp")

    private val unsupportedMimes = setOf("audio/x-ms-wma", "audio/wma")

    /** Lower-case extension (without dot) if [name] has a plausible one (1 to 5 alphanumeric characters). */
    fun extensionOf(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        val ext = name.substring(dot + 1)
        if (ext.length > 5 || !ext.all { it.isLetterOrDigit() }) return null
        return ext.lowercase()
    }

    /** Removes the extension only when it is a known audio or image one (so `Série - 3.5` stays intact). */
    fun stripKnownExtension(name: String): String {
        val ext = extensionOf(name) ?: return name
        val known = ext in supportedExtensions || ext in unsupportedExtensions || ext in imageExtensions
        return if (known) name.substring(0, name.length - ext.length - 1) else name
    }

    fun classify(item: DriveItem): Kind {
        if (item.isFolder || item.mimeType.startsWith("application/vnd.google-apps")) return Kind.OTHER
        val ext = extensionOf(item.name)
        val mime = item.mimeType.lowercase()
        return when {
            ext in supportedExtensions -> Kind.SUPPORTED_AUDIO

            ext in unsupportedExtensions -> Kind.UNSUPPORTED_AUDIO

            ext in imageExtensions -> Kind.IMAGE

            // No known extension: trust the MIME type.
            mime in unsupportedMimes -> Kind.UNSUPPORTED_AUDIO

            mime.startsWith("audio/") -> Kind.SUPPORTED_AUDIO

            mime.startsWith("image/") -> Kind.IMAGE

            else -> Kind.OTHER
        }
    }

    /** Cover candidates, best first: `cover.*` then `folder.*`, then the other images by decreasing size. */
    fun selectCovers(images: List<DriveItem>): List<DriveItem> {
        fun rank(item: DriveItem): Int = when (NaturalSort.fold(stripKnownExtension(item.name)).trim()) {
            "cover" -> 0
            "folder" -> 1
            else -> 2
        }
        return images.sortedWith(
            compareBy<DriveItem> { rank(it) }
                .thenByDescending { it.sizeBytes ?: 0L }
                .then(NaturalSort.by { it.name })
        )
    }
}
