package io.github.camelbaggy.livresaudio.core.playback

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.camelbaggy.livresaudio.core.data.db.BookEntity

/**
 * Minimal browse tree of the media library session: root -> "En cours" -> books. The full Android Auto tree
 * (Recents, all books, chapters) is lot 6. A book is `isPlayable`, `mediaId = bookId`; the service turns it into
 * its playlist when a controller asks to play it.
 */
object LibraryTree {
    const val ROOT_ID = "root"
    const val IN_PROGRESS_ID = "in_progress"

    fun root(title: String): MediaItem = folder(ROOT_ID, title)

    fun inProgress(title: String): MediaItem = folder(IN_PROGRESS_ID, title)

    fun book(book: BookEntity): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(book.title ?: "")
            .setArtist(book.author)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
        book.coverUri?.toUri()?.takeIf { it.scheme in ARTWORK_SCHEMES }?.let { metadata.setArtworkUri(it) }
        return MediaItem.Builder().setMediaId(book.bookId).setMediaMetadata(metadata.build()).build()
    }

    private val ARTWORK_SCHEMES = setOf("http", "https", "content", "file")

    private fun folder(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()
}
