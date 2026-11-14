package io.github.camelbaggy.livresaudio.core.playback

import io.github.camelbaggy.livresaudio.core.data.db.ProgressEntity
import io.github.camelbaggy.livresaudio.core.model.ListeningStatus

/** Where the player must start when a book is loaded. */
data class RestoreTarget(val fileIndex: Int, val positionMs: Long)

/** Pure choice of the start position (CLAUDE.md §4.4, §5.3). The saved position in Room is authoritative. */
object RestorePlanner {
    /**
     * Order: (1) the file with the saved `fileMd5` (or, when the saved md5 is null, the saved Drive file id) and
     * `positionMs`; (2) else `bookPositionMs` located through the durations; (3) else the very start.
     * A FINISHED book restarts from the beginning (the saved end position stays in Room until the user listens
     * again: the zero-position write is refused by the repository, which is the intended safeguard).
     */
    fun plan(progress: ProgressEntity?, book: PlaybackBook, timeline: ChapterTimeline): RestoreTarget {
        val start = RestoreTarget(0, 0)
        if (progress == null || book.files.isEmpty()) return start
        if (progress.status == ListeningStatus.FINISHED) return start
        val byFile = findFile(progress, book.files)
        if (byFile != null) {
            return clamp(RestoreTarget(byFile, progress.positionMs), book)
        }
        val located = timeline.locate(progress.bookPositionMs) ?: return start
        return clamp(RestoreTarget(located.fileIndex, located.positionMs), book)
    }

    private fun findFile(progress: ProgressEntity, files: List<PlaybackFile>): Int? {
        val saved = progress.fileMd5
        val candidates = files.indices.filter { index ->
            if (saved != null) files[index].md5 == saved else files[index].driveFileId == progress.driveFileId
        }
        // Duplicated content (same md5 twice): prefer the saved index when it is one of them.
        return candidates.firstOrNull { it == progress.fileIndex } ?: candidates.firstOrNull()
    }

    private fun clamp(target: RestoreTarget, book: PlaybackBook): RestoreTarget {
        val duration = book.files[target.fileIndex].durationMs
        val position = target.positionMs.coerceAtLeast(0)
        return target.copy(positionMs = if (duration != null) position.coerceAtMost(duration) else position)
    }
}
