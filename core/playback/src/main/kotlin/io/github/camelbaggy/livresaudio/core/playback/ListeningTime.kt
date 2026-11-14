package io.github.camelbaggy.livresaudio.core.playback

import kotlin.math.roundToLong

/**
 * Remaining times for the UI, **in listening time**: at 1.5x, 30 min of audio take 20 min (CLAUDE.md §5.3).
 * Pure functions; positions are the player's (file index + position in the file).
 */
object ListeningTime {
    /** [audioMs] of audio at [speed] (clamped to a valid speed), in milliseconds of listening time. */
    fun atSpeed(audioMs: Long, speed: Float): Long =
        (audioMs.coerceAtLeast(0) / PlaybackSpeed.normalize(speed)).roundToLong()

    /**
     * Remaining listening time of the book, or null when a file duration is unknown (the total is then a lower
     * bound and would mislead).
     */
    fun bookRemainingMs(timeline: ChapterTimeline, fileIndex: Int, positionMs: Long, speed: Float): Long? {
        val total = timeline.totalDurationMs ?: return null
        val audio = (total - timeline.bookPositionMs(fileIndex, positionMs)).coerceAtLeast(0)
        return atSpeed(audio, speed)
    }

    /**
     * Remaining listening time of the current chapter. [fileDurationMs] (the player's duration of the current
     * file) completes the chapter end when the timeline does not know it; null when still unknown.
     */
    fun chapterRemainingMs(
        timeline: ChapterTimeline,
        fileIndex: Int,
        positionMs: Long,
        speed: Float,
        fileDurationMs: Long? = null
    ): Long? {
        val chapter = timeline.chapterAt(fileIndex, positionMs) ?: return null
        val end = chapter.endMs ?: fileDurationMs ?: return null
        return atSpeed((end - positionMs).coerceAtLeast(0), speed)
    }

    /** Duration of the current chapter in audio time (for the chapter progress bar), or null when unknown. */
    fun chapterDurationMs(
        timeline: ChapterTimeline,
        fileIndex: Int,
        positionMs: Long,
        fileDurationMs: Long? = null
    ): Long? {
        val chapter = timeline.chapterAt(fileIndex, positionMs) ?: return null
        val end = chapter.endMs ?: fileDurationMs ?: return null
        return (end - chapter.startMs).coerceAtLeast(0)
    }
}
