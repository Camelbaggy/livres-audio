package io.github.camelbaggy.livresaudio.e2

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.camelbaggy.livresaudio.core.drive.auth.AccountStore
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveRestClient
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveResult
import io.github.camelbaggy.livresaudio.core.drive.rest.describe
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.ConnectedAccount
import io.github.camelbaggy.livresaudio.core.playback.DriveMediaRef
import io.github.camelbaggy.livresaudio.core.playback.DriveMediaUri
import io.github.camelbaggy.livresaudio.core.playback.NetworkMonitor
import io.github.camelbaggy.livresaudio.core.playback.PlaybackEngine
import io.github.camelbaggy.livresaudio.core.playback.PlaybackStatus
import io.github.camelbaggy.livresaudio.core.playback.computePlaybackStatus
import io.github.camelbaggy.livresaudio.core.playback.describePlaybackError
import io.github.camelbaggy.livresaudio.e1.LogLine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AudioFile(val id: String, val name: String, val mimeType: String?, val sizeBytes: Long?, val md5: String?)

/** Everything the player card displays; refreshed twice a second and on every player event. */
data class PlayerSnapshot(
    val status: PlaybackStatus = PlaybackStatus.Paused,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val bufferedPositionMs: Long = 0,
    val aheadMs: Long = 0,
    val speed: Float = 1f
)

/** Single UI state of the E2 trial screen. */
data class E2UiState(
    val accounts: List<ConnectedAccount> = emptyList(),
    val selectedAccountId: AccountId? = null,
    val query: String = "",
    val searching: Boolean = false,
    val searchMessage: String? = null,
    val results: List<AudioFile> = emptyList(),
    val current: AudioFile? = null,
    val player: PlayerSnapshot = PlayerSnapshot(),
    val cacheFileBytes: Long = 0,
    val cacheTotalBytes: Long = 0,
    val online: Boolean = true,
    val log: List<LogLine> = emptyList()
)

private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
private const val TICK_MS = 500L
private const val SEEK_BACK_MS = 10_000L
private const val SEEK_FORWARD_MS = 30_000L
private const val MAX_LOG_LINES = 500
private const val PAGE_SIZE = 50

/**
 * Trial E2 (CLAUDE.md lot 0): plays a Drive file through the authenticated source and the disk cache, and shows
 * what the player and the cache are doing. The player lives in the singleton [PlaybackEngine], not here.
 */
@UnstableApi
@HiltViewModel
class E2ViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val drive: DriveRestClient,
    private val engine: PlaybackEngine,
    private val network: NetworkMonitor,
    store: AccountStore
) : ViewModel() {
    private val local = MutableStateFlow(E2UiState(online = network.isOnlineNow()))
    private val player = engine.player
    private var lastError: String? = null
    private var pendingSeekAtMs: Long? = null
    private var pendingStartAtMs: Long? = null
    private var lastLoadErrorLine: String? = null
    private var loadErrorRepeats = 0

    val state: StateFlow<E2UiState> = combine(local, store.accounts) { l, accounts ->
        val selected = l.selectedAccountId?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.firstOrNull()?.id
        l.copy(accounts = accounts, selectedAccountId = selected)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), E2UiState())

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            log(true, "État du lecteur : ${stateName(playbackState)}")
            if (playbackState == Player.STATE_READY) {
                pendingStartAtMs?.let { log(true, "Prêt à jouer ${elapsedSince(it)} ms après la demande") }
                pendingStartAtMs = null
                pendingSeekAtMs?.let { log(true, "Reprise ${elapsedSince(it)} ms après le déplacement") }
                pendingSeekAtMs = null
                lastError = null
            }
            refresh()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            log(true, if (playWhenReady) "Lecture demandée" else "Pause demandée (motif $reason)")
            refresh()
        }

        override fun onPlayerError(error: PlaybackException) {
            val text = describePlaybackError(error)
            lastError = text
            log(false, "Erreur du lecteur : $text (code ${error.errorCodeName})")
            refresh()
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: java.io.IOException,
            wasCanceled: Boolean
        ) {
            val line = "Erreur de chargement : ${describePlaybackError(error)}"
            if (line == lastLoadErrorLine) {
                loadErrorRepeats++
                if (loadErrorRepeats % LOAD_ERROR_LOG_EVERY != 0) return
                log(false, "$line (x$loadErrorRepeats, nouvelle tentative automatique)")
            } else {
                lastLoadErrorLine = line
                loadErrorRepeats = 1
                log(false, "$line (nouvelle tentative automatique)")
            }
        }
    }

    init {
        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)
        player.currentMediaItem?.let { log(true, "Lecteur déjà actif : lecture en cours conservée") }
        viewModelScope.launch {
            network.online.collect { online ->
                if (online != local.value.online) log(online, if (online) "Réseau : retour" else "Réseau : perdu")
                local.update { it.copy(online = online) }
                refresh()
            }
        }
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(TICK_MS)
            }
        }
        log(true, "Écran E2 prêt (cache : ${formatMegabytes(engine.playbackCache.totalBytes())})")
    }

    override fun onCleared() {
        player.removeListener(playerListener)
        player.removeAnalyticsListener(analyticsListener)
    }

    fun selectAccount(id: AccountId) = local.update { it.copy(selectedAccountId = id) }

    fun setQuery(text: String) = local.update { it.copy(query = text) }

    fun search() {
        val accountId = state.value.selectedAccountId ?: return
        if (local.value.searching) return
        val text = local.value.query
        local.update { it.copy(searching = true, searchMessage = null) }
        log(true, "> Recherche « ${text.trim()} »")
        viewModelScope.launch {
            try {
                when (val result = drive.listFiles(accountId, audioSearchQuery(text), pageSize = PAGE_SIZE)) {
                    is DriveResult.Failure -> {
                        log(false, "Recherche : ${result.error.describe()}")
                        local.update { it.copy(searching = false, searchMessage = result.error.describe()) }
                    }

                    is DriveResult.Success -> {
                        val files = result.value.files.map {
                            AudioFile(it.id, it.name ?: it.id, it.mimeType, it.size?.toLongOrNull(), it.md5Checksum)
                        }.sortedBy { it.name.lowercase() }
                        log(true, "${files.size} fichier(s) audio trouvé(s)")
                        local.update { it.copy(searching = false, results = files, searchMessage = null) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(false, "Recherche : exception ${e.javaClass.simpleName}")
                local.update { it.copy(searching = false) }
            }
        }
    }

    fun play(file: AudioFile) {
        val accountId = state.value.selectedAccountId ?: return
        val ref = DriveMediaRef(accountId.value, file.id, file.md5)
        log(true, "> Lecture de « ${file.name} » (${formatMegabytes(file.sizeBytes)}, ${file.mimeType ?: "type ?"})")
        lastError = null
        pendingStartAtMs = SystemClock.elapsedRealtime()
        pendingSeekAtMs = null
        local.update { it.copy(current = file) }
        player.setMediaItem(engine.mediaItem(ref, file.name))
        player.prepare()
        player.playWhenReady = true
        startService()
        refresh()
    }

    fun togglePlay() {
        if (player.currentMediaItem == null) return
        when {
            lastError != null || player.playbackState == Player.STATE_IDLE -> {
                log(true, "Nouvelle tentative de lecture")
                lastError = null
                pendingStartAtMs = SystemClock.elapsedRealtime()
                player.prepare()
                player.play()
                startService()
            }

            player.playbackState == Player.STATE_ENDED -> {
                seekToMs(0)
                player.play()
            }

            player.playWhenReady -> player.pause()

            else -> {
                player.play()
                startService()
            }
        }
        refresh()
    }

    fun seekBack() = seekToMs((player.currentPosition - SEEK_BACK_MS).coerceAtLeast(0))

    fun seekForward() {
        val duration = player.duration.takeIf { it != C.TIME_UNSET }
        val target = player.currentPosition + SEEK_FORWARD_MS
        seekToMs(if (duration != null) target.coerceAtMost(duration) else target)
    }

    fun seekToMs(positionMs: Long) {
        log(true, "Déplacement vers ${formatDuration(positionMs)}")
        pendingSeekAtMs = SystemClock.elapsedRealtime()
        player.seekTo(positionMs)
        refresh()
    }

    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed, 1f)
        log(true, "Vitesse : ${speed}x")
        refresh()
    }

    fun clearCache() {
        player.stop()
        player.clearMediaItems()
        lastError = null
        engine.playbackCache.clear()
        local.update { it.copy(current = null) }
        log(true, "Cache vidé (reste : ${formatMegabytes(engine.playbackCache.totalBytes())})")
        refresh()
    }

    fun clearLog() = local.update { it.copy(log = emptyList()) }

    private fun refresh() {
        val current = local.value.current
        val online = local.value.online
        val duration = player.duration.takeIf { it != C.TIME_UNSET }
        val status = computePlaybackStatus(player.playbackState, player.playWhenReady, online, lastError)
        val position = player.currentPosition
        val snapshot = PlayerSnapshot(
            status = status,
            positionMs = position,
            durationMs = duration,
            bufferedPositionMs = player.bufferedPosition,
            aheadMs = player.totalBufferedDuration,
            speed = player.playbackParameters.speed
        )
        val fileBytes = current?.let { engine.playbackCache.cachedBytes(it.cacheKey()) } ?: 0
        val total = engine.playbackCache.totalBytes()
        local.update { it.copy(player = snapshot, cacheFileBytes = fileBytes, cacheTotalBytes = total) }
    }

    private fun AudioFile.cacheKey(): String {
        val account = state.value.selectedAccountId?.value.orEmpty()
        return DriveMediaUri.cacheKey(DriveMediaRef(account, id, md5))
    }

    private fun startService() {
        // Plain startService: the app is in the foreground here; Media3 promotes the service to the
        // foreground (notification) as soon as playback starts.
        runCatching { context.startService(Intent(context, TrialPlaybackService::class.java)) }
            .onFailure { log(false, "Service de lecture non démarré : ${it.javaClass.simpleName}") }
    }

    private fun log(ok: Boolean, text: String) {
        val line = LogLine(TIME_FORMAT.format(Instant.now()), ok, text)
        local.update { it.copy(log = (it.log + line).takeLast(MAX_LOG_LINES)) }
    }

    private fun elapsedSince(startMs: Long) = SystemClock.elapsedRealtime() - startMs

    private fun stateName(state: Int) = when (state) {
        Player.STATE_IDLE -> "inactif"
        Player.STATE_BUFFERING -> "tampon en cours"
        Player.STATE_READY -> "prêt"
        Player.STATE_ENDED -> "terminé"
        else -> "inconnu"
    }

    private companion object {
        const val LOAD_ERROR_LOG_EVERY = 5
    }
}
