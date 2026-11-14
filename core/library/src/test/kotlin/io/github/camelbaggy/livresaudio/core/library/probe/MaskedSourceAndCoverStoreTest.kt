package io.github.camelbaggy.livresaudio.core.library.probe

import io.github.camelbaggy.livresaudio.core.library.chapters.CountingSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MaskedSourceAndCoverStoreTest {
    private val data = ByteArray(100) { (it + 1).toByte() }

    @Test
    fun laZoneMasqueeEstRendueEnZerosSansEtreLue() = runTest {
        val inner = CountingSource(data)
        val masked = MaskedSource(inner, 30, 60)

        val got = masked.read(20, 60) // 20..80, à cheval sur le masque
        assertContentEquals(data.copyOfRange(20, 30) + ByteArray(30) + data.copyOfRange(60, 80), got)
        assertEquals(30L, inner.bytesRead) // 10 avant + 20 après, rien du masque

        assertContentEquals(data.copyOfRange(0, 10), masked.read(0, 10))
        assertContentEquals(data.copyOfRange(70, 100), masked.read(70, 50))
        assertContentEquals(ByteArray(10), masked.read(35, 10))
        // Fin de fichier au milieu du masque.
        val short = MaskedSource(inner, 90, 200).read(85, 50)
        assertContentEquals(data.copyOfRange(85, 90) + ByteArray(10), short)
    }

    @Test
    fun coverStoreEcritAtomiquementEtRemplaceLAncienne() {
        val dir = File.createTempFile("covers", "").also { it.delete() }
        try {
            val store = FileCoverStore(dir)
            val jpeg1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3, 4)
            val jpeg2 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 9, 9, 9, 9)

            val uri1 = store.save("book", jpeg1, "image/jpeg")!!
            assertTrue(uri1.startsWith("file:///"), uri1)
            assertContentEquals(jpeg1, File(uri1.removePrefix("file://")).readBytes())
            assertEquals(uri1, store.save("book", jpeg1, null)) // idempotent

            val uri2 = store.save("book", jpeg2, "image/jpeg")!!
            assertNotEquals(uri1, uri2) // une autre image donne une autre URI : pas de cache périmé
            assertEquals(listOf(File(uri2.removePrefix("file://")).name), dir.list()!!.toList())

            assertNull(store.save("book", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), "image/jpeg")) // pas une image
        } finally {
            dir.deleteRecursively()
        }
    }
}
