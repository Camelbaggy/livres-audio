package io.github.camelbaggy.livresaudio.player

import androidx.media3.common.Player
import io.github.camelbaggy.livresaudio.core.model.ListeningSettings
import io.github.camelbaggy.livresaudio.core.playback.ActiveBook
import io.github.camelbaggy.livresaudio.core.playback.BookChapter
import io.github.camelbaggy.livresaudio.core.playback.ChapterTimeline
import io.github.camelbaggy.livresaudio.core.playback.FilePosition
import io.github.camelbaggy.livresaudio.core.playback.PlaybackBook
import io.github.camelbaggy.livresaudio.core.playback.PlaybackFile
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerLogicTest {
    private val files = listOf(
        PlaybackFile("f0", null, 100_000, "a"),
        PlaybackFile("f1", null, 200_000, "b")
    )
    private val chapters = listOf(
        BookChapter(0, "Un", 0, 0, 40_000),
        BookChapter(1, "Deux", 0, 40_000, 100_000),
        BookChapter(2, "Trois", 1, 0, 200_000)
    )
    private val active = ActiveBook(
        PlaybackBook("b", "acc", "Livre", "Auteur", null, files, chapters),
        ChapterTimeline(files, chapters)
    )

    @Test
    fun `computeSeek traverse les fichiers et borne aux extremites`() {
        val durations = listOf<Long?>(100_000, 200_000)
        assertEquals(FilePosition(0, 40_000), computeSeek(0, 10_000, 30_000, durations))
        assertEquals(FilePosition(1, 10_000), computeSeek(0, 90_000, 20_000, durations))
        assertEquals(FilePosition(0, 95_000), computeSeek(1, 5_000, -10_000, durations))
        assertEquals(FilePosition(0, 0), computeSeek(0, 5_000, -10_000, durations))
        assertEquals(FilePosition(1, 200_000), computeSeek(1, 190_000, 30_000, durations))
        assertEquals(FilePosition(0, 0), computeSeek(1, 5_000, -500_000, durations))
    }

    @Test
    fun `computeSeek avec duree inconnue`() {
        assertEquals(FilePosition(0, 40_000), computeSeek(0, 10_000, 30_000, listOf(null, 5_000)))
        assertEquals(FilePosition(0, 0), computeSeek(1, 5_000, -10_000, listOf(null, 5_000)))
    }

    @Test
    fun `statut affiche`() {
        assertEquals(
            PlayerStatus.PLAYING,
            statusOf(ControllerState(playbackState = Player.STATE_READY, playWhenReady = true))
        )
        assertEquals(
            PlayerStatus.PAUSED,
            statusOf(ControllerState(playbackState = Player.STATE_READY, playWhenReady = false))
        )
        assertEquals(PlayerStatus.LOADING, statusOf(ControllerState(playbackState = Player.STATE_BUFFERING)))
        assertEquals(
            PlayerStatus.WAITING_NETWORK,
            statusOf(ControllerState(playbackState = Player.STATE_BUFFERING, online = false))
        )
        assertEquals(PlayerStatus.NOT_FOUND, statusOf(ControllerState(error = "X / Y (HTTP 404)")))
        assertEquals(PlayerStatus.ERROR, statusOf(ControllerState(error = "X / Y")))
    }

    @Test
    fun `etat du lecteur - chapitre courant, position et temps restant a la vitesse`() {
        val controller = ControllerState(
            connected = true,
            fileIndex = 0,
            positionMs = 70_000,
            playbackState = Player.STATE_READY,
            playWhenReady = true,
            speed = 2f
        )
        val state = buildPlayerState(controller, active, ListeningSettings())
        assertEquals(1, state.chapterIndex)
        assertEquals("Deux", state.chapterTitle)
        assertEquals(30_000, state.chapterPositionMs)
        assertEquals(60_000, state.chapterDurationMs)
        // (300 000 - 70 000) / 2
        assertEquals(115_000, state.remainingBookMs)
        assertEquals(PlayerStatus.PLAYING, state.status)
    }

    @Test
    fun `l'etat du flux publie par le service l'emporte sur l'etat brut du lecteur`() {
        val waiting = ControllerState(
            playbackState = Player.STATE_IDLE,
            playWhenReady = true,
            error = "PlaybackException / UnknownHostException",
            waitingForNetwork = true,
            availableMs = 720_000
        )
        assertEquals(PlayerStatus.WAITING_NETWORK, statusOf(waiting))
        // Offline from the OS point of view or not, the service says what it is.
        assertEquals(
            PlayerStatus.WAITING_NETWORK,
            statusOf(ControllerState(playbackState = Player.STATE_BUFFERING, online = true, waitingForNetwork = true))
        )
        assertEquals(PlayerStatus.NOT_FOUND, statusOf(ControllerState(error = "X / Y", notFound = true)))
        assertEquals(
            PlayerStatus.NOT_FOUND,
            statusOf(ControllerState(waitingForNetwork = true, notFound = true))
        )
    }

    @Test
    fun `la duree disponible n'est exposee que pendant l'attente du reseau`() {
        val waiting = ControllerState(
            connected = true,
            playbackState = Player.STATE_BUFFERING,
            playWhenReady = true,
            waitingForNetwork = true,
            availableMs = 720_000
        )
        val state = buildPlayerState(waiting, active, ListeningSettings())
        assertEquals(PlayerStatus.WAITING_NETWORK, state.status)
        assertEquals(720_000L, state.availableMs)
        assertEquals(true, state.status.isStreamProblem)

        val healthy = buildPlayerState(waiting.copy(waitingForNetwork = false), active, ListeningSettings())
        assertEquals(null, healthy.availableMs)
        assertEquals(false, healthy.status.isStreamProblem)
    }

    @Test
    fun `sans livre charge`() {
        val state = buildPlayerState(ControllerState(), null, ListeningSettings())
        assertEquals(false, state.loaded)
    }
}
