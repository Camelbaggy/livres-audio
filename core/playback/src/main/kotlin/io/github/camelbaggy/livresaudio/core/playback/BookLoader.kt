package io.github.camelbaggy.livresaudio.core.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import io.github.camelbaggy.livresaudio.core.data.db.BookWithProgress
import io.github.camelbaggy.livresaudio.core.data.repo.LibraryRepository
import io.github.camelbaggy.livresaudio.core.data.repo.ProgressRepository
import io.github.camelbaggy.livresaudio.core.model.ListeningStatus
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** A book ready to be handed to the player: its items and where to start (Room's position is authoritative). */
@UnstableApi
class LoadedBook(
    val book: PlaybackBook,
    val timeline: ChapterTimeline,
    val items: List<MediaItem>,
    val target: RestoreTarget
)

/** Loads a book from Room and decides the start position ([RestorePlanner]). */
@UnstableApi
class BookLoader @Inject constructor(private val library: LibraryRepository, private val progress: ProgressRepository) {
    /** Null when the book is unknown or has no file. */
    suspend fun load(bookId: String): LoadedBook? {
        val row = library.getBook(bookId) ?: return null
        val book = row.toPlaybackBook()
        if (book.files.isEmpty()) return null
        val timeline = ChapterTimeline(book.files, book.embeddedChapters)
        val target = RestorePlanner.plan(progress.get(bookId), book, timeline)
        return LoadedBook(book, timeline, BookPlaylist.build(book), target)
    }

    /** Books being listened to, the most recently played first. */
    suspend fun inProgress(): List<BookWithProgress> = library.observeLibrary().first()
        .filter { it.progress?.status == ListeningStatus.IN_PROGRESS }
        .sortedByDescending { it.progress?.lastPlayedAt ?: 0L }
}
