package io.github.camelbaggy.livresaudio.core.playback

import androidx.media3.common.util.UnstableApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class BookPlaylistTest {
    @Test
    fun `one item per file with drive uri, cache key and file media id`() {
        val items = BookPlaylist.build(book().copy(coverUri = "https://exemple.invalid/c.jpg"))
        assertEquals(3, items.size)
        val first = items[1]
        assertEquals("book-1/f1", first.mediaId)
        assertEquals("drive://acc/f1?md5=md5-1", first.localConfiguration?.uri.toString())
        assertEquals("drive:f1:md5-1", first.localConfiguration?.customCacheKey)
        assertEquals("Titre", first.mediaMetadata.title.toString())
        assertEquals("Auteur", first.mediaMetadata.artist.toString())
        assertEquals("https://exemple.invalid/c.jpg", first.mediaMetadata.artworkUri.toString())
        assertTrue(first.mediaMetadata.isPlayable == true)
    }

    @Test
    fun `an unreadable cover scheme is left out and a missing title falls back to the file name`() {
        val items = BookPlaylist.build(book().copy(title = null, coverUri = "drive://acc/cover"))
        assertNull(items[0].mediaMetadata.artworkUri)
        assertEquals("Fichier 0", items[0].mediaMetadata.title.toString())
    }

    @Test
    fun `media ids`() {
        assertEquals("b/f", BookPlaylist.fileMediaId("b", "f"))
        assertTrue(BookPlaylist.isFileMediaId("b/f"))
        assertFalse(BookPlaylist.isFileMediaId("b"))
        assertEquals("b", BookPlaylist.bookIdOf("b/f"))
        assertEquals("b", BookPlaylist.bookIdOf("b"))
    }
}
