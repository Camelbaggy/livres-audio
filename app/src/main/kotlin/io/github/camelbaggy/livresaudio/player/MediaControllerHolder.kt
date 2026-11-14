package io.github.camelbaggy.livresaudio.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.camelbaggy.livresaudio.core.playback.ActiveBookHolder
import io.github.camelbaggy.livresaudio.core.playback.LivresAudioPlaybackService
import io.github.camelbaggy.livresaudio.core.playback.NetworkMonitor
import io.github.camelbaggy.livresaudio.core.playback.describePlaybackError
import io.github.camelbaggy.livresaudio.core.playback.sendClearSpeedOverride
import io.github.camelbaggy.livresaudio.core.playback.sendSetSpeed
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Connection of the UI to the playback service through a `MediaController`. Its life is tied to the activity:
 * [connect] in `onStart`, [release] in `onStop` (the service keeps playing). Commands sent before the connection is
 * ready are queued and run on connect. All calls on the main thread.
 */
@OptIn(UnstableApi::class)
@Singleton
class MediaControllerHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activeBookHolder: ActiveBookHolder,
    private val network: NetworkMonitor
) : PlaybackGateway,
    PlaybackCommands {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(ControllerState())
    override val state: StateFlow<ControllerState> = mutableState.asStateFlow()

    private var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()
    private var pollJob: Job? = null
    private var networkJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, LivresAudioPlaybackService::class.java))
        val connecting = MediaController.Builder(context, token).buildAsync()
        future = connecting
        connecting.addListener({
            try {
                val connected = connecting.get()
                controller = connected
                connected.addListener(listener)
                val queued = pending.toList()
                pending.clear()
                queued.forEach { it(connected) }
                publish()
                startPolling()
            } catch (_: Exception) {
                future = null
                mutableState.value = ControllerState()
            }
        }, ContextCompat.getMainExecutor(context))
        networkJob =
            scope.launch { network.online.collect { online -> mutableState.update { it.copy(online = online) } } }
    }

    fun release() {
        pollJob?.cancel()
        networkJob?.cancel()
        pending.clear()
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        mutableState.value = ControllerState()
    }

    private fun run(block: (MediaController) -> Unit) {
        val connected = controller
        if (connected != null) block(connected) else pending += block
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                publish()
                delay(POLL_MS)
            }
        }
    }

    private fun publish() {
        val c = controller ?: return
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it >= 0 }
        mutableState.update {
            it.copy(
                connected = true,
                fileIndex = c.currentMediaItemIndex,
                positionMs = c.currentPosition.coerceAtLeast(0),
                fileDurationMs = duration,
                playbackState = c.playbackState,
                playWhenReady = c.playWhenReady,
                speed = c.playbackParameters.speed,
                error = c.playerError?.let { error -> describePlaybackError(error) }
            )
        }
    }

    override fun playBook(bookId: String) = run { c ->
        val loaded = activeBookHolder.active.value?.book?.bookId == bookId && c.mediaItemCount > 0
        if (!loaded) {
            c.setMediaItem(MediaItem.Builder().setMediaId(bookId).build())
        }
        c.prepare()
        c.play()
    }

    override fun play() = run { it.play() }

    override fun pause() = run { it.pause() }

    override fun seekTo(fileIndex: Int, positionMs: Long) = run { it.seekTo(fileIndex, positionMs) }

    override fun seekBy(deltaMs: Long) = run { c ->
        val durations = activeBookHolder.active.value?.book?.files?.map { it.durationMs }
            ?: listOf(c.duration.takeIf { it != C.TIME_UNSET })
        val target = computeSeek(c.currentMediaItemIndex, c.currentPosition, deltaMs, durations)
        c.seekTo(target.fileIndex, target.positionMs)
    }

    override fun resetPosition() = run { c ->
        c.sendCustomCommand(
            SessionCommand(LivresAudioPlaybackService.COMMAND_RESET_POSITION, Bundle.EMPTY),
            Bundle.EMPTY
        )
    }

    override fun setSpeed(speed: Float) = run { it.sendSetSpeed(speed) }

    override fun clearSpeedOverride() = run { it.sendClearSpeedOverride() }

    private companion object {
        const val POLL_MS = 500L
    }
}
