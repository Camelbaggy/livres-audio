package io.github.camelbaggy.livresaudio.core.playback

/** A position in the book: file index in the playlist + position inside that file. */
data class FilePosition(val fileIndex: Int, val positionMs: Long)

/** A chapter of the timeline, resolved ([endMs] never null). */
data class TimelineChapter(
    val indexInBook: Int,
    val title: String?,
    val fileIndex: Int,
    val startMs: Long,
    /** End inside the file; `null` only when the file duration is unknown (then: up to the end of the file). */
    val endMs: Long?
) {
    val durationMs: Long? get() = endMs?.let { (it - startMs).coerceAtLeast(0) }
}

/**
 * Pure chapter / book-position arithmetic over a playlist of one `MediaItem` per file (CLAUDE.md §5.3).
 *
 * Chapters: the embedded ones of each file when it has some; otherwise one chapter for the whole file. Unknown file
 * durations are tolerated: they count as 0 in sums (so [bookPositionMs] is then a lower bound).
 * No Android type: tested with tables of examples.
 */
class ChapterTimeline(private val files: List<PlaybackFile>, embedded: List<BookChapter>) {
    val chapters: List<TimelineChapter> = buildChapters(files, embedded)

    private val chaptersByFile: Map<Int, List<TimelineChapter>> = chapters.groupBy { it.fileIndex }

    /** Sum of the durations of all files, or null if any is unknown. */
    val totalDurationMs: Long? =
        if (files.isNotEmpty() && files.all { it.durationMs != null }) files.sumOf { it.durationMs ?: 0L } else null

    /** The chapter containing the position: the last one starting at or before it (the first one if none). */
    fun chapterAt(fileIndex: Int, positionMs: Long): TimelineChapter? {
        val inFile = chaptersByFile[fileIndex] ?: return null
        return inFile.lastOrNull { it.startMs <= positionMs } ?: inFile.firstOrNull()
    }

    /** Start of the next chapter, or null when already in the last one. */
    fun next(fileIndex: Int, positionMs: Long): FilePosition? {
        val current = chapterAt(fileIndex, positionMs) ?: return null
        return chapters.getOrNull(current.indexInBook + 1)?.start()
    }

    /**
     * "Previous": back to the start of the current chapter if we are in it for more than [thresholdMs], else the
     * start of the chapter before; in the very first chapter, its start.
     */
    fun previous(fileIndex: Int, positionMs: Long, thresholdMs: Long = PREVIOUS_THRESHOLD_MS): FilePosition? {
        val current = chapterAt(fileIndex, positionMs) ?: return null
        val inChapter = positionMs - current.startMs
        val target = if (inChapter > thresholdMs) current else chapters.getOrNull(current.indexInBook - 1) ?: current
        return target.start()
    }

    fun positionInChapterMs(fileIndex: Int, positionMs: Long): Long {
        val chapter = chapterAt(fileIndex, positionMs) ?: return positionMs.coerceAtLeast(0)
        return (positionMs - chapter.startMs).coerceAtLeast(0)
    }

    /** `bookPositionMs` of CLAUDE.md §4.2: durations of the preceding files + position in the current one. */
    fun bookPositionMs(fileIndex: Int, positionMs: Long): Long {
        val before = files.take(fileIndex.coerceIn(0, files.size)).sumOf { it.durationMs ?: 0L }
        return before + positionMs.coerceAtLeast(0)
    }

    /**
     * Inverse of [bookPositionMs] (restoration by book position, CLAUDE.md §4.4). Walks the files while their
     * duration is known and fully behind the position; the remainder lands in the first file of unknown duration,
     * or at the end of the last file. Empty book: null.
     */
    fun locate(bookPositionMs: Long): FilePosition? {
        if (files.isEmpty()) return null
        var remaining = bookPositionMs.coerceAtLeast(0)
        for ((index, file) in files.withIndex()) {
            val duration = file.durationMs ?: return FilePosition(index, remaining)
            val isLast = index == files.lastIndex
            if (remaining < duration || isLast) {
                return FilePosition(index, remaining.coerceAtMost(duration))
            }
            remaining -= duration
        }
        return null
    }

    private fun TimelineChapter.start() = FilePosition(fileIndex, startMs)

    companion object {
        /** "Previous chapter" returns to the chapter start when we have been in it for more than 3 s. */
        const val PREVIOUS_THRESHOLD_MS = 3_000L

        private fun buildChapters(files: List<PlaybackFile>, embedded: List<BookChapter>): List<TimelineChapter> {
            val byFile = embedded.filter { it.fileIndex in files.indices }.groupBy { it.fileIndex }
            val result = mutableListOf<TimelineChapter>()
            for ((fileIndex, file) in files.withIndex()) {
                val own = byFile[fileIndex].orEmpty().sortedBy { it.startMs }
                if (own.isEmpty()) {
                    result += TimelineChapter(result.size, file.name, fileIndex, 0L, file.durationMs)
                    continue
                }
                for ((position, chapter) in own.withIndex()) {
                    val end = chapter.endMs ?: own.getOrNull(position + 1)?.startMs ?: file.durationMs
                    result += TimelineChapter(result.size, chapter.title, fileIndex, chapter.startMs, end)
                }
            }
            return result
        }
    }
}
