package io.github.camelbaggy.livresaudio.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.camelbaggy.livresaudio.core.model.ListeningSettings
import io.github.camelbaggy.livresaudio.core.model.ListeningSettingsStore
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val gateway: PlaybackGateway,
    private val commands: PlaybackCommands,
    private val activeBook: ActiveBookSource,
    settingsStore: ListeningSettingsStore
) : ViewModel() {
    val state: StateFlow<PlayerUiState> = combine(
        gateway.state,
        activeBook.active,
        settingsStore.settings
    ) { controller, active, settings -> buildPlayerState(controller, active, settings) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerUiState())

    fun togglePlay() {
        if (state.value.isPlaying || gateway.state.value.playWhenReady) gateway.pause() else gateway.play()
    }

    fun skipBack() = gateway.seekBy(-state.value.skipBackMs)

    fun skipForward() = gateway.seekBy(state.value.skipForwardMs)

    fun nextChapter() {
        val timeline = activeBook.active.value?.timeline ?: return
        val controller = gateway.state.value
        timeline.next(controller.fileIndex, controller.positionMs)?.let { gateway.seekTo(it.fileIndex, it.positionMs) }
    }

    fun previousChapter() {
        val timeline = activeBook.active.value?.timeline ?: return
        val controller = gateway.state.value
        timeline.previous(controller.fileIndex, controller.positionMs)
            ?.let { gateway.seekTo(it.fileIndex, it.positionMs) }
    }

    /** [fraction] 0..1 of the CURRENT chapter (the seek bar is the chapter's). */
    fun seekToChapterFraction(fraction: Float) {
        val timeline = activeBook.active.value?.timeline ?: return
        val controller = gateway.state.value
        val chapter = timeline.chapterAt(controller.fileIndex, controller.positionMs) ?: return
        val duration = chapter.durationMs ?: return
        gateway.seekTo(chapter.fileIndex, chapter.startMs + (duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun seekToChapter(index: Int) {
        val chapter = activeBook.active.value?.timeline?.chapters?.getOrNull(index) ?: return
        gateway.seekTo(chapter.fileIndex, chapter.startMs)
    }

    fun setSpeed(speed: Float) {
        commands.setSpeed(speed.coerceIn(ListeningSettings.MIN_SPEED, ListeningSettings.MAX_SPEED))
    }
}
