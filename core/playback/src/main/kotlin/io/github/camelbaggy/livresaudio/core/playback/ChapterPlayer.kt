package io.github.camelbaggy.livresaudio.core.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * The player the session exposes (notification, lock screen, Bluetooth, controllers): "next/previous" mean
 * chapter next/previous through the [ChapterTimeline] (CLAUDE.md §5.3). Other commands pass through unchanged.
 * Without a timeline (nothing loaded) it behaves like the wrapped player.
 */
@UnstableApi
class ChapterPlayer(player: Player, private val timeline: () -> ChapterTimeline?) : ForwardingPlayer(player) {
    override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
        .addIf(Player.COMMAND_SEEK_TO_NEXT, timeline() != null)
        .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, timeline() != null)
        .build()

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun seekToNext() {
        val chapters = timeline() ?: return super.seekToNext()
        chapters.next(currentMediaItemIndex, currentPosition)?.let { seekTo(it.fileIndex, it.positionMs) }
    }

    override fun seekToPrevious() {
        val chapters = timeline() ?: return super.seekToPrevious()
        chapters.previous(currentMediaItemIndex, currentPosition)?.let { seekTo(it.fileIndex, it.positionMs) }
    }

    override fun seekToNextMediaItem() = seekToNext()

    override fun seekToPreviousMediaItem() = seekToPrevious()
}
