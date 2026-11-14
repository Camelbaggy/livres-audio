package io.github.camelbaggy.livresaudio.core.model

/** A Drive file or folder as listed by `files.list` (only the fields the library needs). */
data class DriveItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val parentId: String?,
    val sizeBytes: Long? = null,
    val md5: String? = null,
    val modifiedTimeMs: Long? = null
) {
    val isFolder: Boolean get() = mimeType == FOLDER_MIME_TYPE

    companion object {
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
    }
}

/** How a book is anchored in Drive (CLAUDE.md §4.4): a folder of parts, or one single audio file. */
sealed interface BookAnchor {
    /** Drive id the [bookIdFor] derivation is based on. */
    val driveId: String

    data class Folder(val folderId: String) : BookAnchor {
        override val driveId: String get() = folderId
    }

    data class SingleFile(val fileId: String, val parentFolderId: String) : BookAnchor {
        override val driveId: String get() = fileId
    }
}

/**
 * A book found while walking a watched root, before any persistence.
 * [files] are the audio files in listening order (disc, then track or natural sort).
 */
data class DiscoveredBook(
    val anchor: BookAnchor,
    val rootId: String,
    val files: List<DriveItem>,
    /** Names of the grouping folders between the root and the book, outermost first (hints: author, series). */
    val groupingNames: List<String>,
    val parsed: ParsedName,
    /** Candidate cover images found next to the audio files, best first. */
    val coverCandidates: List<DriveItem>,
    /** Files with a recognised but unsupported audio format (e.g. `.wma`). */
    val unsupportedFiles: List<DriveItem> = emptyList()
)
