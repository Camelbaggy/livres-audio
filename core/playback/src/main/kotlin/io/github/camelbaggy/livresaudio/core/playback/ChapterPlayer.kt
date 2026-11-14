package io.github.camelbaggy.livresaudio.core.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.camelbaggy.livresaudio.core.model.ListeningSettings

/**
 * The outermost player the session exposes (notification, lock screen, Bluetooth, controllers), CLAUDE.md §5.3.
 * It owns everything that depends on the book's [ChapterTimeline] and on the listening settings:
 *
 * - **Skips**: `seekForward()` / `seekBack()` move by `skipForwardMs` / `skipBackMs` of the *live* settings
 *   (read at each call, so a change applies at once) and cross file boundaries.
 * - **Headset / notification buttons**: `seekToNext()` / `seekToPrevious()` (what the media buttons and the
 *   notification send) are skips by default, or chapter moves when `headsetButtonsAsChapters`.
 * - **Chapters, always**: `seekToNextMediaItem()` / `seekToPreviousMediaItem()` are always chapter moves, whatever
 *   the setting. The UI's chapter buttons must use them (or the equivalent controller calls).
 *
 * Without a timeline (nothing loaded) it behaves like the wrapped player.
 */
@UnstableApi
class ChapterPlayer(
    player: Player,
    private val timeline: () -> ChapterTimeline?,
    private val settings: () -> ListeningSettings = { ListeningSettings() }
) : ForwardingPlayer(player) {
    override fun getAvailableCommands(): Player.Commands {
        val loaded = timeline() != null
        return super.getAvailableCommands().buildUpon()
            .addIf(Player.COMMAND_SEEK_TO_NEXT, loaded)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, loaded)
            .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, loaded)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, loaded)
            .build()
    }

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun getSeekForwardIncrement(): Long = settings().skipForwardMs

    override fun getSeekBackIncrement(): Long = settings().skipBackMs

    override fun seekForward() {
        val chapters = timeline() ?: return super.seekForward()
        skip(chapters, settings().skipForwardMs)
    }

    override fun seekBack() {
        val chapters = timeline() ?: return super.seekBack()
        skip(chapters, -settings().skipBackMs)
    }

    override fun seekToNext() {
        if (settings().headsetButtonsAsChapters) nextChapter() else seekForward()
    }

    override fun seekToPrevious() {
        if (settings().headsetButtonsAsChapters) previousChapter() else seekBack()
    }

    override fun seekToNextMediaItem() = nextChapter()

    override fun seekToPreviousMediaItem() = previousChapter()

    private fun skip(chapters: ChapterTimeline, deltaMs: Long) {
        val target = chapters.offset(currentMediaItemIndex, currentPosition, deltaMs)
        seekTo(target.fileIndex, target.positionMs)
    }

    private fun nextChapter() {
        val chapters = timeline() ?: return super.seekToNextMediaItem()
        chapters.next(currentMediaItemIndex, currentPosition)?.let { seekTo(it.fileIndex, it.positionMs) }
    }

    private fun previousChapter() {
        val chapters = timeline() ?: return super.seekToPreviousMediaItem()
        chapters.previous(currentMediaItemIndex, currentPosition)?.let { seekTo(it.fileIndex, it.positionMs) }
    }
}
