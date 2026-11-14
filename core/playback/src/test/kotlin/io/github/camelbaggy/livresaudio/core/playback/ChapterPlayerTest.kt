package io.github.camelbaggy.livresaudio.core.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ChapterPlayerTest {
    private val theBook = book(
        chapters = listOf(BookChapter(0, "A", 1, 0, 200_000), BookChapter(1, "B", 1, 200_000, null))
    )
    private val timeline = ChapterTimeline(theBook.files, theBook.embeddedChapters)

    private fun player(): ExoPlayer {
        val exo = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        // Unprepared player: positions are tracked without any media to load.
        exo.setMediaItems(
            theBook.files.map {
                MediaItem.Builder().setMediaId(it.driveFileId).setUri("drive://a/${it.driveFileId}").build()
            }
        )
        return exo
    }

    @Test
    fun `next and previous move by chapter`() {
        val exo = player()
        val chapterPlayer = ChapterPlayer(exo) { timeline }
        exo.seekTo(1, 50_000)
        chapterPlayer.seekToNext()
        assertEquals(1, exo.currentMediaItemIndex)
        assertEquals(200_000L, exo.currentPosition)

        chapterPlayer.seekToNext() // last chapter of the book is file 2: reachable from file 1 chapter B
        assertEquals(2, exo.currentMediaItemIndex)
        chapterPlayer.seekToNext() // already in the last chapter: no move
        assertEquals(2, exo.currentMediaItemIndex)
        assertEquals(0L, exo.currentPosition)

        exo.seekTo(1, 250_000)
        chapterPlayer.seekToPrevious() // > 3 s in: start of chapter B
        assertEquals(200_000L, exo.currentPosition)
        chapterPlayer.seekToPrevious() // at its start: chapter A
        assertEquals(0L, exo.currentPosition)
        assertEquals(1, exo.currentMediaItemIndex)
        exo.release()
    }

    @Test
    fun `next and previous commands are always available once a book is loaded`() {
        val exo = player()
        var current: ChapterTimeline? = null
        val chapterPlayer = ChapterPlayer(exo) { current }
        current = timeline
        exo.seekTo(2, 0)
        assertTrue(chapterPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(chapterPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        exo.release()
    }

    @Test
    fun `snapshot reflects the player`() {
        val exo = player()
        exo.seekTo(2, 42_000)
        val snapshot = snapshotOf(exo)
        assertEquals("f2", snapshot.mediaId)
        assertEquals(2, snapshot.mediaItemIndex)
        assertEquals(3, snapshot.itemCount)
        assertEquals(42_000L, snapshot.positionMs)
        assertEquals(KeeperPlayerState.IDLE, snapshot.state)
        exo.release()
    }

    @Test
    fun `player state and discontinuity mapping`() {
        assertEquals(KeeperPlayerState.BUFFERING, keeperStateOf(Player.STATE_BUFFERING))
        assertEquals(KeeperPlayerState.READY, keeperStateOf(Player.STATE_READY))
        assertEquals(KeeperPlayerState.ENDED, keeperStateOf(Player.STATE_ENDED))
        assertEquals(KeeperPlayerState.IDLE, keeperStateOf(Player.STATE_IDLE))
        assertEquals(DiscontinuityKind.SEEK, discontinuityKindOf(Player.DISCONTINUITY_REASON_SEEK))
        assertEquals(DiscontinuityKind.OTHER, discontinuityKindOf(Player.DISCONTINUITY_REASON_AUTO_TRANSITION))
        assertEquals(DiscontinuityKind.OTHER, discontinuityKindOf(Player.DISCONTINUITY_REASON_REMOVE))
    }
}
