package io.github.camelbaggy.livresaudio.core.playback

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi

/**
 * A book = one `MediaItem` per file, played gaplessly by the player (CLAUDE.md §5.3).
 *
 * `mediaId` of a file item is `"<bookId>/<driveFileId>"` ([fileMediaId]); it lets the position keeper check that
 * what the player plays really is the expected file of the expected book. A book itself, in the library tree, has
 * `mediaId = bookId` (no slash).
 */
@UnstableApi
object BookPlaylist {
    private const val SEPARATOR = "/"
    private val ARTWORK_SCHEMES = setOf("http", "https", "content", "file")

    fun fileMediaId(bookId: String, driveFileId: String): String = "$bookId$SEPARATOR$driveFileId"

    /** True for ids built by [fileMediaId] (as opposed to a bare `bookId`). */
    fun isFileMediaId(mediaId: String): Boolean = mediaId.contains(SEPARATOR)

    /** The book id of a file media id or of a bare book id. */
    fun bookIdOf(mediaId: String): String = mediaId.substringBefore(SEPARATOR)

    fun build(book: PlaybackBook): List<MediaItem> = book.files.map { file ->
        val ref = DriveMediaRef(book.accountId, file.driveFileId, file.md5)
        MediaItem.Builder()
            .setMediaId(fileMediaId(book.bookId, file.driveFileId))
            .setUri(DriveMediaUri.build(ref))
            .setCustomCacheKey(DriveMediaUri.cacheKey(ref))
            .setMediaMetadata(metadata(book, file))
            .build()
    }

    private fun metadata(book: PlaybackBook, file: PlaybackFile): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setTitle(book.title ?: file.name)
            .setAlbumTitle(book.title)
            .setArtist(book.author)
            .setIsPlayable(true)
            .setIsBrowsable(false)
        artworkUri(book.coverUri)?.let { builder.setArtworkUri(it) }
        return builder.build()
    }

    /** Only schemes the notification's bitmap loader can read: a `drive://` cover would just fail. */
    private fun artworkUri(coverUri: String?): android.net.Uri? {
        if (coverUri.isNullOrBlank()) return null
        val uri = coverUri.toUri()
        return uri.takeIf { it.scheme in ARTWORK_SCHEMES }
    }
}
