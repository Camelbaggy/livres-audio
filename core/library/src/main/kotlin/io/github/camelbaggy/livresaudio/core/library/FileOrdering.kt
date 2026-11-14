package io.github.camelbaggy.livresaudio.core.library

import io.github.camelbaggy.livresaudio.core.model.DriveItem

/**
 * Extension point: order of the audio files of one book (or of one disc).
 * Task 1.5 can supply an implementation that uses disc and track numbers from the tags
 * when they are consistent across the book, and falls back to [NaturalFileOrdering] otherwise.
 */
fun interface FileOrdering {
    fun order(files: List<DriveItem>): List<DriveItem>
}

/** Natural sort of the file name without its extension (`2` before `10`, `00 - Prologue` before `01`). */
object NaturalFileOrdering : FileOrdering {
    override fun order(files: List<DriveItem>): List<DriveItem> = files.sortedWith(
        NaturalSort.by<DriveItem> { AudioFormats.stripKnownExtension(it.name) }
            .then(NaturalSort.by { it.name })
    )
}
