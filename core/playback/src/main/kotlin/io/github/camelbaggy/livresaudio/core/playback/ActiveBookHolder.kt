package io.github.camelbaggy.livresaudio.core.playback

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The book currently loaded in the playback service, with its chapter timeline. */
data class ActiveBook(val book: PlaybackBook, val timeline: ChapterTimeline)

/**
 * In-process window on the service's loaded book, for the UI (same process as the service): the UI combines
 * `ActiveBook.timeline` with the `MediaController` position (`currentMediaItemIndex` = file index) to show the
 * chapter title, the position in the chapter and in the book.
 */
@Singleton
class ActiveBookHolder @Inject constructor() {
    private val mutable = MutableStateFlow<ActiveBook?>(null)
    val active: StateFlow<ActiveBook?> = mutable.asStateFlow()

    internal fun set(book: ActiveBook?) {
        mutable.value = book
    }
}
