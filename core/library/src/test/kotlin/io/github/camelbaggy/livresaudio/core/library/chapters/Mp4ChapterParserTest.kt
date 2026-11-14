package io.github.camelbaggy.livresaudio.core.library.chapters

import io.github.camelbaggy.livresaudio.core.library.chapters.Mp4Builder.audioTrackReferencing
import io.github.camelbaggy.livresaudio.core.library.chapters.Mp4Builder.box
import io.github.camelbaggy.livresaudio.core.library.chapters.Mp4Builder.chpl
import io.github.camelbaggy.livresaudio.core.library.chapters.Mp4Builder.ftyp
import io.github.camelbaggy.livresaudio.core.library.chapters.Mp4Builder.moovWithChpl
import io.github.camelbaggy.livresaudio.core.library.chapters.Mp4Builder.textSample
import io.github.camelbaggy.livresaudio.core.library.chapters.Mp4Builder.textTrack
import io.github.camelbaggy.livresaudio.core.library.chapters.Mp4Builder.u32
import io.github.camelbaggy.livresaudio.core.library.chapters.Mp4Builder.u64
import io.github.camelbaggy.livresaudio.core.model.EmbeddedChapter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class Mp4ChapterParserTest {
    private val parser = Mp4ChapterParser()

    private val expected = listOf(
        Triple("Prologue : l'épée", 0L, 2000L),
        Triple("Chapitre 2 — Œuvre à Châteauroux", 2000L, 5500L),
        Triple("Épilogue", 5500L, 8000L)
    )

    private fun assertChapters(
        want: List<Triple<String?, Long, Long?>>,
        got: List<EmbeddedChapter>,
        toleranceMs: Long = 1
    ) {
        assertEquals(want.size, got.size, "nombre de chapitres : $got")
        want.zip(got).forEach { (w, g) ->
            assertEquals(w.first, g.title)
            assertTrue(kotlin.math.abs(w.second - g.startMs) <= toleranceMs, "début ${g.startMs} != ${w.second}")
            if (w.third == null) {
                assertNull(g.endMs)
            } else {
                assertTrue(kotlin.math.abs(w.third!! - g.endMs!!) <= toleranceMs, "fin ${g.endMs} != ${w.third}")
            }
        }
    }

    @Test
    fun fichiersFfmpegDonnentLesTroisChapitres() = runTest {
        // Tableau : fichier -> mécanisme présent
        listOf(
            "qt-chapitres.mov", // QuickTime + chpl
            "qt-seul.m4b", // QuickTime seul
            "chpl-moov-fin.m4b", // moov après mdat
            "chpl-moov-debut.m4b" // moov avant mdat (faststart)
        ).forEach { name ->
            val src = CountingSource(resource(name))
            assertChapters(expected, parser.parse(src))
        }
    }

    @Test
    fun chplSeulNaPasDeFinPourLeDernierChapitre() = runTest {
        val got = parser.parse(CountingSource(resource("chpl-seul.m4b")))
        assertChapters(listOf(expected[0], expected[1], Triple(expected[2].first, 5500L, null)), got)
    }

    @Test
    fun moovEnDebutEtEnFinSontBienTestes() {
        fun firstTypes(name: String): List<String> {
            val d = resource(name)
            val out = mutableListOf<String>()
            var o = 0
            while (o + 8 <= d.size) {
                val size = ((d[o].toInt() and 0xFF) shl 24) or ((d[o + 1].toInt() and 0xFF) shl 16) or
                    ((d[o + 2].toInt() and 0xFF) shl 8) or (d[o + 3].toInt() and 0xFF)
                out += String(d, o + 4, 4, Charsets.ISO_8859_1)
                o += size
            }
            return out
        }
        assertTrue(firstTypes("chpl-moov-fin.m4b").indexOf("moov") > firstTypes("chpl-moov-fin.m4b").indexOf("mdat"))
        assertTrue(
            firstTypes("chpl-moov-debut.m4b").indexOf("moov") < firstTypes("chpl-moov-debut.m4b").indexOf("mdat")
        )
    }

    @Test
    fun sansChapitresDonneListeVide() = runTest {
        assertEquals(emptyList(), parser.parse(CountingSource(resource("sans-chapitres.m4b"))))
    }

    @Test
    fun moovApresUnEnormeMdatNeLitQueLEssentiel() = runTest {
        val moov = moovWithChpl(chpl(1, listOf(0L to "Début", 60_000L to "Suite", 3_600_000L to "Fin")))
        val mdatSize = 5L * 1024 * 1024 * 1024
        val mdatHeader = u32(1) + "mdat".toByteArray() + u64(mdatSize)
        val src = CountingSource(
            listOf(
                CountingSource.Data(ftyp()),
                CountingSource.Data(mdatHeader),
                CountingSource.Gap(mdatSize - 16),
                CountingSource.Data(moov)
            )
        )
        val result = parser.parse(src)
        assertChapters(
            listOf(Triple("Début", 0L, 60_000L), Triple("Suite", 60_000L, 3_600_000L), Triple("Fin", 3_600_000L, null)),
            result
        )
        assertTrue(src.bytesRead < 4096 + moov.size, "octets lus : ${src.bytesRead}")
        assertTrue(src.readCalls < 10, "lectures : ${src.readCalls}")
    }

    @Test
    fun moovAvecTailleZeroJusquALaFin() = runTest {
        val moov = moovWithChpl(chpl(1, listOf(0L to "A", 1000L to "B")))
        val unbounded = u32(0) + moov.copyOfRange(4, moov.size)
        val file = ftyp() + box("free", ByteArray(10)) + unbounded
        assertChapters(listOf(Triple("A", 0L, 1000L), Triple("B", 1000L, null)), parser.parse(CountingSource(file)))
    }

    @Test
    fun chplVersionsEtDispositions() = runTest {
        val entries = listOf(0L to "Intro é", 5_000L to "Suite")
        listOf(
            chpl(1, entries), // version 1 : 4 octets réservés
            chpl(0, entries, reserved = false), // version 0 : compteur direct
            chpl(0, entries, reserved = true), // version 0 avec réservé (tolérance)
            chpl(1, entries, reserved = false) // version 1 sans réservé (tolérance)
        ).forEachIndexed { i, c ->
            val file = ftyp() + moovWithChpl(c)
            val got = parser.parse(CountingSource(file))
            assertChapters(listOf(Triple("Intro é", 0L, 5000L), Triple("Suite", 5000L, null)), got)
            assertTrue(i >= 0)
        }
    }

    @Test
    fun pisteQuickTimeSurPlusieursChunksCo64EtUtf16() = runTest {
        val samples = listOf(
            textSample("Un"),
            textSample("Deux — été", utf16 = true),
            textSample("Trois")
        )
        val ftyp = ftyp()
        // mdat : en-tête 8 puis échantillons ; les chunks : {1 échantillon}, {2 échantillons}
        val mdatStart = ftyp.size.toLong()
        val dataStart = mdatStart + 8
        val off0 = dataStart
        val off1 = off0 + samples[0].size
        val mdat = box("mdat", samples.fold(ByteArray(0)) { a, b -> a + b })
        val track = textTrack(
            id = 2,
            timescale = 600,
            durations = listOf(600, 1200, 600),
            sampleSizes = samples.map { it.size },
            chunkOffsets = listOf(off0, off1),
            samplesPerChunk = listOf(1, 2),
            wide = true
        )
        val moov = box("moov", audioTrackReferencing(1, 2), track)
        val got = parser.parse(CountingSource(ftyp + mdat + moov))
        assertChapters(
            listOf(Triple("Un", 0L, 1000L), Triple("Deux — été", 1000L, 3000L), Triple("Trois", 3000L, 4000L)),
            got
        )
    }

    @Test
    fun laPisteQuickTimeEstPrefereeAuChpl() = runTest {
        val sample = textSample("Piste QT")
        val ftyp = ftyp()
        val mdat = box("mdat", sample)
        val track =
            textTrack(2, 1000, listOf(2000), listOf(sample.size), listOf(ftyp.size + 8L), listOf(1), wide = false)
        val udta = box("udta", chpl(1, listOf(0L to "Nero")))
        val moov = box("moov", audioTrackReferencing(1, 2), track, udta)
        val got = parser.parse(CountingSource(ftyp + mdat + moov))
        assertEquals(listOf("Piste QT"), got.map { it.title })
    }

    @Test
    fun quickTimeIllisibleRetombeSurChpl() = runTest {
        // tref/chap désigne une piste absente
        val udta = box("udta", chpl(1, listOf(0L to "Nero", 1000L to "Deux")))
        val moov = box("moov", audioTrackReferencing(1, 99), udta)
        val got = parser.parse(CountingSource(ftyp() + moov))
        assertEquals(listOf("Nero", "Deux"), got.map { it.title })
    }

    @Test
    fun normalisationTitresVidesDoublonsEtDureesNulles() = runTest {
        val moov = moovWithChpl(
            chpl(
                1,
                listOf(
                    0L to "  ",
                    0L to "Doublon utile",
                    1000L to "B",
                    1000L to "B bis",
                    2000L to "C"
                )
            )
        )
        val got = parser.parse(CountingSource(ftyp() + moov))
        assertEquals(listOf("Doublon utile", "B", "C"), got.map { it.title })
        assertEquals(listOf(1000L, 2000L, null), got.map { it.endMs })

        val vide = parser.parse(CountingSource(ftyp() + moovWithChpl(chpl(1, listOf(0L to "", 10L to "x")))))
        assertEquals(listOf(null, "x"), vide.map { it.title })
    }

    @Test
    fun tailleInconnueEtMoovAZeroNeBoucleJamais() = runTest {
        val moov = moovWithChpl(chpl(1, listOf(0L to "A")))
        val file = ftyp() + moov
        assertEquals(listOf("A"), parser.parse(UnknownSizeSource(file)).map { it.title })
        val unbounded = ftyp() + u32(0) + moov.copyOfRange(4, moov.size)
        assertEquals(emptyList(), parser.parse(UnknownSizeSource(unbounded)))
    }

    @Test
    fun fichiersTronquesOuCorrompusDonnentListeVideSansException() = runTest {
        listOf("chpl-moov-fin.m4b", "qt-chapitres.mov", "chpl-moov-debut.m4b").forEach { name ->
            val full = resource(name)
            // moov (en fin ou en début) incomplet ou fichier coupé au milieu d'une boîte
            for (cut in listOf(0, 1, 7, 8, 20, 100, full.size / 2, full.size - 1)) {
                val result = parser.parse(CountingSource(full.copyOf(cut)))
                if (name == "chpl-moov-fin.m4b" || name == "qt-chapitres.mov") {
                    assertEquals(emptyList(), result, "$name coupé à $cut")
                }
            }
            // corruptions aléatoires reproductibles : jamais d'exception, jamais de blocage
            val rnd = Random(42)
            repeat(300) {
                val copy = full.copyOf()
                repeat(1 + rnd.nextInt(8)) { copy[rnd.nextInt(copy.size)] = rnd.nextInt(256).toByte() }
                parser.parse(CountingSource(copy))
            }
        }
    }

    @Test
    fun taillesIncoherentesDonnentListeVide() = runTest {
        val bogus = listOf(
            u32(7) + "free".toByteArray(), // taille < en-tête
            u32(1) + "mdat".toByteArray() + u64(4), // 64 bits trop petit
            u32(1) + "mdat".toByteArray() + u64(Long.MAX_VALUE), // énorme
            u32(0xFFFFFFF0L) + "moov".toByteArray() + ByteArray(8)
        )
        bogus.forEach { assertEquals(emptyList(), parser.parse(CountingSource(ftyp() + it))) }
        assertEquals(emptyList(), parser.parse(CountingSource(ByteArray(0))))
    }
}
