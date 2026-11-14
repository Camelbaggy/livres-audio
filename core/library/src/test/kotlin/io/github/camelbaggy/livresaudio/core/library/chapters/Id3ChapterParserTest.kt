package io.github.camelbaggy.livresaudio.core.library.chapters

import io.github.camelbaggy.livresaudio.core.model.EmbeddedChapter
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class Id3ChapterParserTest {
    private val parser = Id3ChapterParser()

    private val expectedTitles = listOf("Prologue : l'épée", "Chapitre 2 — Œuvre à Châteauroux", "Épilogue")
    private val expectedStarts = listOf(0L, 2000L, 5500L)
    private val expectedEnds = listOf(2000L, 5500L, 8000L)

    @Test
    fun fichiersFfmpegV23EtV24() = runTest {
        listOf("mp3-chap-v23.mp3", "mp3-chap-v24.mp3").forEach { name ->
            val src = CountingSource(resource(name))
            val got = parser.parse(src)
            assertEquals(expectedTitles, got.map { it.title }, name)
            assertEquals(expectedStarts, got.map { it.startMs }, name)
            assertEquals(expectedEnds, got.map { it.endMs }, name)
        }
    }

    @Test
    fun sansChapitresOuSansEnteteDonneListeVide() = runTest {
        assertEquals(emptyList(), parser.parse(CountingSource(resource("sans-chapitres.mp3"))))
        assertEquals(emptyList(), parser.parse(CountingSource(ByteArray(5))))
        assertEquals(emptyList(), parser.parse(CountingSource("ID3\u0005\u0000".toByteArray() + ByteArray(20))))
    }

    @Test
    fun grosTagAvecImageNeLitQueLesEnTetesEtLesChap() = runTest {
        val apic = frame4("APIC", ByteArray(3_000_000) { 1 })
        val tag = tag4(apic, chap4("c1", 0, 1000, "Un"), chap4("c2", 1000, 2000, "Deux"))
        val src = CountingSource(listOf(CountingSource.Data(tag), CountingSource.Gap(1_000_000)))
        val got = parser.parse(src)
        assertEquals(listOf("Un", "Deux"), got.map { it.title })
        assertTrue(src.bytesRead < 100_000, "octets lus : ${src.bytesRead} pour un tag de ${tag.size}")
    }

    @Test
    fun ctocImposeLOrdreEtLesEncodagesSontDecodes() = runTest {
        val tag = tag4(
            chap4("b", 5000, 9000, "Béta", enc = 3),
            chap4("a", 0, 5000, "Alpha ñ", enc = 1),
            chap4("c", 9000, 12000, "Gamma ü", enc = 2),
            chap4("d", 12000, 13000, "Delta é", enc = 0),
            ctoc4("toc", listOf("a", "b", "c", "d"))
        )
        val got = parser.parse(CountingSource(tag))
        assertEquals(listOf("Alpha ñ", "Béta", "Gamma ü", "Delta é"), got.map { it.title })
        assertEquals(listOf(0L, 5000L, 9000L, 12000L), got.map { it.startMs })
    }

    @Test
    fun sansCtocTriParDebutEtFinManquanteEgaleAuSuivant() = runTest {
        val tag = tag4(
            chap4("z", 4000, 0, "Dernier"),
            chap4("y", 0, 0, "Premier"),
            chap4("x", 2000, 0, "Milieu")
        )
        val got = parser.parse(CountingSource(tag))
        assertEquals(listOf("Premier", "Milieu", "Dernier"), got.map { it.title })
        assertEquals(listOf(2000L, 4000L, null), got.map { it.endMs })
    }

    @Test
    fun ctocImbriqueEtOrdreIncoherentRetombeSurLeTri() = runTest {
        val nested = tag4(
            chap4("a", 0, 10, "A"),
            chap4("b", 10, 20, "B"),
            ctoc4("sub", listOf("a", "b"), flags = 0),
            ctoc4("top", listOf("sub"), flags = 2)
        )
        assertEquals(listOf("A", "B"), parser.parse(CountingSource(nested)).map { it.title })

        val bad = tag4(chap4("a", 0, 10, "A"), chap4("b", 10, 20, "B"), ctoc4("top", listOf("b", "a")))
        assertEquals(listOf("A", "B"), parser.parse(CountingSource(bad)).map { it.title })
    }

    @Test
    fun enteteEtendueV24EtV23SontSautees() = runTest {
        val frames = chap4("a", 0, 10, "Ext")
        val ext4 = byteArrayOf(0, 0, 0, 6, 1, 0) // taille synchsafe 6 (incluse), 1 octet de drapeaux
        val tag4 = id3Header(4, 0x40, ext4.size + frames.size) + ext4 + frames
        assertEquals(listOf("Ext"), parser.parse(CountingSource(tag4)).map { it.title })

        val frames3 = chap3("a", 0, 10, "Ext3")
        val ext3 = byteArrayOf(0, 0, 0, 6, 0, 0, 0, 0, 0, 0) // taille 6 hors champ, puis 6 octets
        val tag3 = id3Header(3, 0x40, ext3.size + frames3.size) + ext3 + frames3
        assertEquals(listOf("Ext3"), parser.parse(CountingSource(tag3)).map { it.title })
    }

    @Test
    fun desynchronisationV23NiveauTagEtV24NiveauTrame() = runTest {
        // v2.3 : tag entier désynchronisé ; le début 0x000000FF force des 0xFF dans la trame
        val chap = chap3("a", 255, 511, "Sync")
        val unsynced = unsync(chap)
        assertTrue(unsynced.size > chap.size)
        val tag3 = id3Header(3, 0x80, unsynced.size) + unsynced
        val got3 = parser.parse(CountingSource(tag3))
        assertEquals(listOf(255L), got3.map { it.startMs })
        assertEquals(listOf("Sync"), got3.map { it.title })

        // v2.4 : drapeau de désynchronisation sur la trame ; la taille est celle des octets stockés
        val body = chapBody("a", 255, 511, "Sync4", 4)
        val stored = unsync(body)
        val frame = byteArrayOf('C'.code.toByte(), 'H'.code.toByte(), 'A'.code.toByte(), 'P'.code.toByte()) +
            synchsafe(stored.size) + byteArrayOf(0, 0x02) + stored
        val got4 = parser.parse(CountingSource(id3Header(4, 0, frame.size) + frame))
        assertEquals(listOf(255L), got4.map { it.startMs })
        assertEquals(listOf("Sync4"), got4.map { it.title })
    }

    @Test
    fun decodageTexteEtDesynchronisationSontPurs() {
        assertContentEquals(byteArrayOf(1, -1, 2), parser.removeUnsynchronisation(byteArrayOf(1, -1, 0, 2)))
        assertContentEquals(byteArrayOf(-1), parser.removeUnsynchronisation(byteArrayOf(-1)))
        listOf(
            byteArrayOf(0, 0x45, 0xE9.toByte(), 0, 0x58) to "Eé", // Latin-1, terminateur
            byteArrayOf(3, 0xC3.toByte(), 0xA9.toByte()) to "é",
            byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte(), 0xE9.toByte(), 0, 0, 0) to "é",
            byteArrayOf(1, 0xFE.toByte(), 0xFF.toByte(), 0, 0xE9.toByte()) to "é",
            byteArrayOf(2, 0, 0xE9.toByte()) to "é"
        ).forEach { (input, want) -> assertEquals(want, parser.decodeTextFrame(input)) }
        assertEquals(null, parser.decodeTextFrame(byteArrayOf(9, 1, 2)))
        assertEquals(null, parser.decodeTextFrame(ByteArray(0)))
    }

    @Test
    fun tronquesEtCorrompusDonnentListeVideSansException() = runTest {
        val full = resource("mp3-chap-v24.mp3")
        // Coupé dans le tag : jamais d'exception ; tag incomplet -> liste vide
        for (cut in listOf(0, 3, 9, 10, 11, 25, 60)) {
            assertEquals(emptyList(), parser.parse(CountingSource(full.copyOf(cut))), "coupé à $cut")
        }
        val rnd = Random(7)
        repeat(500) {
            val copy = full.copyOf(600) // l'en-tête et les trames
            repeat(1 + rnd.nextInt(6)) { copy[rnd.nextInt(copy.size)] = rnd.nextInt(256).toByte() }
            parser.parse(CountingSource(copy))
        }
    }

    // --- Constructeurs de tags --------------------------------------------------------------------

    private fun synchsafe(v: Int) = byteArrayOf(
        ((v shr 21) and 0x7F).toByte(),
        ((v shr 14) and 0x7F).toByte(),
        ((v shr 7) and 0x7F).toByte(),
        (
            v and
                0x7F
            ).toByte()
    )

    private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun id3Header(major: Int, flags: Int, size: Int) =
        "ID3".toByteArray() + byteArrayOf(major.toByte(), 0, flags.toByte()) + synchsafe(size)

    private fun unsync(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (i in b.indices) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() &&
                (i + 1 == b.size || (b[i + 1].toInt() and 0xE0) == 0xE0 || b[i + 1].toInt() == 0)
            ) {
                out.write(0)
            }
        }
        return out.toByteArray()
    }

    private fun frame4(id: String, body: ByteArray) = id.toByteArray() + synchsafe(body.size) + byteArrayOf(0, 0) + body

    private fun frame3(id: String, body: ByteArray) = id.toByteArray() + be32(body.size) + byteArrayOf(0, 0) + body

    private fun text(s: String, enc: Int): ByteArray = when (enc) {
        0 -> s.toByteArray(Charsets.ISO_8859_1) + 0
        1 -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)
        2 -> s.toByteArray(Charsets.UTF_16BE) + byteArrayOf(0, 0)
        else -> s.toByteArray(Charsets.UTF_8) + 0
    }

    private fun chapBody(id: String, start: Int, end: Int, title: String, major: Int, enc: Int = 3): ByteArray {
        val tit2Body = byteArrayOf(enc.toByte()) + text(title, enc)
        val sub = if (major == 4) frame4("TIT2", tit2Body) else frame3("TIT2", tit2Body)
        return id.toByteArray() + 0 + be32(start) + be32(end) + be32(-1) + be32(-1) + sub
    }

    private fun chap4(id: String, start: Int, end: Int, title: String, enc: Int = 3) =
        frame4("CHAP", chapBody(id, start, end, title, 4, enc))

    private fun chap3(id: String, start: Int, end: Int, title: String) =
        frame3("CHAP", chapBody(id, start, end, title, 3))

    private fun ctoc4(id: String, children: List<String>, flags: Int = 3): ByteArray {
        var body = id.toByteArray() + 0 + byteArrayOf(flags.toByte(), children.size.toByte())
        children.forEach { body += it.toByteArray() + 0 }
        return frame4("CTOC", body)
    }

    private fun tag4(vararg frames: ByteArray): ByteArray {
        val payload = frames.fold(ByteArray(0)) { a, b -> a + b } + ByteArray(32) // remplissage
        return id3Header(4, 0, payload.size) + payload
    }

    @Suppress("unused")
    private fun chapters(vararg c: EmbeddedChapter) = c.toList()
}
