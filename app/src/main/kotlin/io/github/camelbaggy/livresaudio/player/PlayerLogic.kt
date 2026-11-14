package io.github.camelbaggy.livresaudio.player

import androidx.media3.common.Player
import io.github.camelbaggy.livresaudio.core.model.ListeningSettings
import io.github.camelbaggy.livresaudio.core.playback.ActiveBook
import io.github.camelbaggy.livresaudio.core.playback.FilePosition
import io.github.camelbaggy.livresaudio.core.playback.ListeningTime
import io.github.camelbaggy.livresaudio.core.playback.PlaybackStatus
import io.github.camelbaggy.livresaudio.core.playback.computePlaybackStatus

/** Status shown on the player screen and in the notification (CLAUDE.md §5.4). */
enum class PlayerStatus { PLAYING, PAUSED, LOADING, WAITING_NETWORK, NOT_FOUND, ERROR, ENDED }

data class ChapterRow(val index: Int, val title: String?, val durationMs: Long?)

data class PlayerUiState(
    val loaded: Boolean = false,
    val bookId: String = "",
    val title: String? = null,
    val author: String? = null,
    val coverUri: String? = null,
    val chapters: List<ChapterRow> = emptyList(),
    val chapterIndex: Int = 0,
    val chapterPositionMs: Long = 0,
    /** Null when unknown (file not probed yet). */
    val chapterDurationMs: Long? = null,
    /** Remaining time of the book at the current speed; null when the total duration is unknown. */
    val remainingBookMs: Long? = null,
    val status: PlayerStatus = PlayerStatus.PAUSED,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val skipBackMs: Long = ListeningSettings().skipBackMs,
    val skipForwardMs: Long = ListeningSettings().skipForwardMs
) {
    val chapterTitle: String? get() = chapters.getOrNull(chapterIndex)?.title
}

/** Maps the raw player state to the displayed status; an error mentioning HTTP 404 is "file not found". Pure. */
fun statusOf(state: ControllerState): PlayerStatus = when (
    val status = computePlaybackStatus(state.playbackState, state.playWhenReady, state.online, state.error)
) {
    PlaybackStatus.Playing -> PlayerStatus.PLAYING
    PlaybackStatus.Paused -> PlayerStatus.PAUSED
    PlaybackStatus.Loading -> PlayerStatus.LOADING
    PlaybackStatus.WaitingForNetwork -> PlayerStatus.WAITING_NETWORK
    PlaybackStatus.Ended -> PlayerStatus.ENDED
    is PlaybackStatus.Error -> if ("404" in status.description) PlayerStatus.NOT_FOUND else PlayerStatus.ERROR
}

/**
 * Target of a skip of [deltaMs] from ([fileIndex], [positionMs]) over files of known/unknown [fileDurations]:
 * goes through file boundaries, stops at the start of the book and at the end of the last file. Pure.
 */
fun computeSeek(fileIndex: Int, positionMs: Long, deltaMs: Long, fileDurations: List<Long?>): FilePosition {
    var index = fileIndex.coerceIn(0, (fileDurations.size - 1).coerceAtLeast(0))
    var position = positionMs + deltaMs
    while (position < 0 && index > 0) {
        val previous = fileDurations[index - 1] ?: return FilePosition(index - 1, 0)
        index -= 1
        position += previous
    }
    if (position < 0) return FilePosition(index, 0)
    while (index < fileDurations.lastIndex) {
        val duration = fileDurations[index] ?: break
        if (position < duration) break
        position -= duration
        index += 1
    }
    val last = fileDurations.getOrNull(index)
    return FilePosition(index, if (last != null) position.coerceAtMost(last) else position)
}

/** Builds the player screen state from the controller, the loaded book and the settings. Pure. */
fun buildPlayerState(controller: ControllerState, active: ActiveBook?, settings: ListeningSettings): PlayerUiState {
    if (active == null) {
        return PlayerUiState(
            speed = controller.speed,
            skipBackMs = settings.skipBackMs,
            skipForwardMs = settings.skipForwardMs
        )
    }
    val timeline = active.timeline
    val current = timeline.chapterAt(controller.fileIndex, controller.positionMs)
    return PlayerUiState(
        loaded = true,
        bookId = active.book.bookId,
        title = active.book.title,
        author = active.book.author,
        coverUri = active.book.coverUri,
        chapters = timeline.chapters.map { ChapterRow(it.indexInBook, it.title, it.durationMs) },
        chapterIndex = current?.indexInBook ?: 0,
        chapterPositionMs = timeline.positionInChapterMs(controller.fileIndex, controller.positionMs),
        chapterDurationMs = current?.durationMs,
        remainingBookMs = ListeningTime.bookRemainingMs(
            timeline,
            controller.fileIndex,
            controller.positionMs,
            controller.speed
        ),
        status = statusOf(controller),
        isPlaying = controller.playbackState == Player.STATE_READY && controller.playWhenReady,
        speed = controller.speed,
        skipBackMs = settings.skipBackMs,
        skipForwardMs = settings.skipForwardMs
    )
}
