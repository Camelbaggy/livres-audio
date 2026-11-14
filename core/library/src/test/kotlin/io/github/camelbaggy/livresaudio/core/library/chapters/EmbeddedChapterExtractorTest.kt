package io.github.camelbaggy.livresaudio.core.library.chapters

import io.github.camelbaggy.livresaudio.core.model.EmbeddedChapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class EmbeddedChapterExtractorTest {
    private val extractor = EmbeddedChapterExtractor()

    @Test
    fun aiguilleParSignatureQuelQueSoitLeMime() = runTest {
        // Tableau : fichier, mime annoncé
        listOf(
            "chpl-moov-fin.m4b" to "audio/mpeg", // mime faux, signature ftyp
            "qt-chapitres.mov" to null,
            "mp3-chap-v24.mp3" to "audio/mp4", // mime faux, signature ID3
            "mp3-chap-v23.mp3" to "audio/mpeg"
        ).forEach { (name, mime) ->
            val got = extractor.extract(CountingSource(resource(name)), mime)
            assertEquals(3, got.size, name)
            assertEquals("Épilogue", got.last().title, name)
        }
    }

    @Test
    fun mimeSeulSertSansSignatureEtLeResteDonneVide() = runTest {
        val empty = emptyList<EmbeddedChapter>()
        assertEquals(empty, extractor.extract(CountingSource(ByteArray(64)), "audio/mp4"))
        assertEquals(empty, extractor.extract(CountingSource(ByteArray(64)), null))
        assertEquals(empty, extractor.extract(CountingSource(ByteArray(0)), "audio/ogg"))
        assertEquals(empty, extractor.extract(CountingSource(resource("sans-chapitres.m4b")), "audio/x-m4b"))
        assertEquals(empty, extractor.extract(CountingSource(resource("sans-chapitres.mp3")), "audio/mpeg"))
    }
}
