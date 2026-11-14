package io.github.camelbaggy.livresaudio.core.playback

import androidx.media3.common.Player

/** What the player screen shows (CLAUDE.md §5.4). */
sealed interface PlaybackStatus {
    data object Playing : PlaybackStatus

    data object Paused : PlaybackStatus

    data object Loading : PlaybackStatus

    data object WaitingForNetwork : PlaybackStatus

    data object Ended : PlaybackStatus

    data class Error(val description: String) : PlaybackStatus
}

/** Pure mapping from the raw player state to the displayed status. */
fun computePlaybackStatus(playbackState: Int, playWhenReady: Boolean, online: Boolean, error: String?): PlaybackStatus =
    when {
        error != null -> PlaybackStatus.Error(error)

        playbackState == Player.STATE_BUFFERING ->
            if (online) PlaybackStatus.Loading else PlaybackStatus.WaitingForNetwork

        playbackState == Player.STATE_ENDED -> PlaybackStatus.Ended

        playbackState == Player.STATE_READY && playWhenReady -> PlaybackStatus.Playing

        else -> PlaybackStatus.Paused
    }

/** Short French description of a player error: type and HTTP code only, never a URL nor a token. */
fun describePlaybackError(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    val http = chain.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()
    val code = http?.let { " (HTTP ${it.responseCode})" }.orEmpty()
    val root = chain.last().javaClass.simpleName
    return "${error.javaClass.simpleName} / $root$code"
}
