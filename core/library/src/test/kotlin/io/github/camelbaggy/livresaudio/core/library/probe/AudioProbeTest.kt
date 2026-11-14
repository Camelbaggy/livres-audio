package io.github.camelbaggy.livresaudio.core.library.probe

import io.github.camelbaggy.livresaudio.core.library.chapters.CountingSource
import io.github.camelbaggy.livresaudio.core.library.chapters.Mp4Builder
import io.github.camelbaggy.livresaudio.core.library.chapters.resource
import io.github.camelbaggy.livresaudio.core.model.ByteRangeSource
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

internal fun probeResource(name: String): ByteArray =
    CountingSource::class.java.getResourceAsStream("/probe/$name")?.use { it.readBytes() }
        ?: error("ressource de test absente : $name")

class AudioProbeTest {
    private val probe = AudioProbe()

    private fun assertNear(expectedMs: Long, actual: Long?, toleranceMs: Long = 80, message: String = "") {
        assertNotNull(actual, message)
        assertTrue(kotlin.math.abs(actual - expectedMs) <= toleranceMs, "$message : $actual ms, attendu $expectedMs")
    }

    @Test
    fun m4bEtiquettesCompletesEtCouvertureLocaliseeSansEtreLue() = runTest {
        for (name in listOf("complet.m4b", "complet-faststart.m4b")) {
            val bytes = probeResource(name)
            val source = CountingSource(bytes)
            val r = probe.probe(source, "audio/mp4")

            assertEquals(ProbeFormat.MP4, r.format, name)
            assertNear(10_000, r.durationMs, message = name)
            val t = r.tags
            assertEquals("Chapitre un", t.title)
            assertEquals("Le Livre d'essai", t.album)
            assertEquals("Auteur Été", t.artist)
            assertEquals("Auteur Été", t.albumArtist)
            assertEquals("Narrateur Œil", t.composer)
            assertEquals(3, t.track)
            assertEquals(12, t.trackTotal)
            assertEquals(2, t.disc)
            assertEquals(3, t.discTotal)
            assertEquals(2014, t.year)
            assertEquals("Un résumé fictif.", t.description)
            val cover = assertNotNull(t.cover, name)
            val jpeg = probeResource("cover.jpg")
            assertEquals(jpeg.size.toLong(), cover.length)
            assertEquals("image/jpeg", cover.mimeType)
            assertContentEquals(jpeg, bytes.copyOfRange(cover.offset.toInt(), (cover.offset + cover.length).toInt()))
            // La couverture (84 Ko) n'est lue ni par les étiquettes ni par les chapitres.
            assertTrue(source.bytesRead < 12_000, "$name : ${source.bytesRead} octets lus")
        }
    }

    @Test
    fun m4bAvecChapitresLesRemonte() = runTest {
        val r = probe.probe(CountingSource(resource("chpl-moov-fin.m4b")), null)
        assertEquals(3, r.chapters.size)
        assertEquals("Épilogue", r.chapters.last().title)
        assertNear(8_000, r.durationMs)
        assertEquals("Livre d'essai", r.tags.title)
        assertEquals("Auteur Été", r.tags.artist)
    }

    @Test
    fun mp3CbrAvecEnteteInfoDureeExacteEtTagsId3v23() = runTest {
        val bytes = probeResource("cbr-info-v23.mp3")
        val source = CountingSource(bytes)
        val r = probe.probe(source, "audio/mpeg")

        assertEquals(ProbeFormat.MP3, r.format)
        assertNear(10_000, r.durationMs, 60)
        val t = r.tags
        assertEquals("Chapitre un", t.title)
        assertEquals("Le Livre d'essai", t.album)
        assertEquals("Auteur Été", t.artist)
        assertEquals("Auteur Été", t.albumArtist)
        assertEquals("Narrateur Œil", t.composer)
        assertEquals(3, t.track)
        assertEquals(12, t.trackTotal)
        assertEquals(2, t.disc)
        assertEquals(2014, t.year)
        assertEquals("Un résumé fictif.", t.description)
        assertEquals("La Saga", t.series)
        assertEquals(2.5, t.seriesIndex)
        assertEquals("Voix Claire", t.narrator)
        val cover = assertNotNull(t.cover)
        val jpeg = probeResource("cover.jpg")
        assertEquals(jpeg.size.toLong(), cover.length)
        assertContentEquals(jpeg, bytes.copyOfRange(cover.offset.toInt(), (cover.offset + cover.length).toInt()))
        assertTrue(source.bytesRead < 70_000, "${source.bytesRead} octets lus sur ${bytes.size}")
    }

    @Test
    fun mp3VbrAvecXing() = runTest {
        val r = probe.probe(CountingSource(probeResource("vbr-xing.mp3")), "audio/mpeg")
        assertNear(10_000, r.durationMs, 60)
        assertEquals("Le Livre d'essai", r.tags.album)
    }

    @Test
    fun mp3SansXingDureeEstimeeParLeDebitEtId3v24() = runTest {
        val r = probe.probe(CountingSource(probeResource("cbr-sans-xing-v24.mp3")), "audio/mpeg")
        assertNear(10_000, r.durationMs, 150)
        assertEquals("Chapitre un", r.tags.title)
        assertEquals(2014, r.tags.year)
        assertEquals(3, r.tags.track)
        assertNull(r.tags.cover)
    }

    @Test
    fun mp3NuSansEtiquettes() = runTest {
        val r = probe.probe(CountingSource(probeResource("nu.mp3")), null)
        assertEquals(ProbeFormat.MP3, r.format)
        assertEquals(AudioTags(), r.tags)
        assertNear(10_000, r.durationMs, 150)
    }

    @Test
    fun mp3Mpeg2() = runTest {
        val r = probe.probe(CountingSource(probeResource("mpeg2.mp3")), "audio/mpeg")
        assertNear(10_000, r.durationMs, 150)
    }

    @Test
    fun mp3AvecChapitresId3() = runTest {
        val r = probe.probe(CountingSource(resource("mp3-chap-v24.mp3")), "audio/mpeg")
        assertEquals(3, r.chapters.size)
    }

    @Test
    fun formatsNonAnalysesDonnentUneDureeInconnue() = runTest {
        val wav = "RIFF".toByteArray() + ByteArray(4) + "WAVEfmt ".toByteArray() + ByteArray(64)
        val ogg = "OggS".toByteArray() + ByteArray(64)
        for ((bytes, mime) in listOf(wav to "audio/wav", ogg to "audio/ogg", ByteArray(0) to null)) {
            val r = probe.probe(CountingSource(bytes), mime)
            assertEquals(ProbeFormat.OTHER, r.format)
            assertNull(r.durationMs)
            assertEquals(AudioTags(), r.tags)
        }
    }

    @Test
    fun fichiersTronquesNeLevantAucuneException() = runTest {
        for (name in listOf("complet.m4b", "complet-faststart.m4b", "cbr-info-v23.mp3", "cbr-sans-xing-v24.mp3")) {
            val full = probeResource(name)
            for (cut in listOf(0, 3, 9, 11, 40, 700, 5_000, full.size / 2, full.size - 1)) {
                val r = probe.probe(CountingSource(full.copyOf(cut)), null)
                assertNotNull(r, "$name coupé à $cut")
            }
        }
    }

    @Test
    fun donneesAleatoiresNeLevantAucuneException() = runTest {
        val random = kotlin.random.Random(42)
        repeat(200) {
            val bytes = random.nextBytes(random.nextInt(600))
            // Force les signatures pour entrer dans les analyseurs.
            val head = listOf(
                "ID3\u0004".toByteArray(Charsets.ISO_8859_1),
                "\u0000\u0000\u0000 ftyp".toByteArray(),
                byteArrayOf()
            )
                .random(random)
            probe.probe(CountingSource(head + bytes), "audio/mpeg")
        }
    }

    @Test
    fun m4bGeantEtiquettesSeLitEnPeuDoctetsEtLesEtiquettesLibresSontLues() = runTest {
        val ilst = Mp4Builder.box(
            "ilst",
            item("©nam", 1, "Titre du livre".toByteArray()),
            item("©alb", 1, "Album".toByteArray()),
            item("trkn", 0, byteArrayOf(0, 0, 0, 5, 0, 9, 0, 0)),
            item("disk", 0, byteArrayOf(0, 0, 0, 1, 0, 2)),
            item("©wrt", 1, "Compositeur".toByteArray()),
            item("ldes", 1, "Long résumé".toByteArray()),
            item("desc", 1, "Court".toByteArray()),
            item("©mvn", 1, "La Série".toByteArray()),
            item("mvin", 21, byteArrayOf(0, 3)),
            freeform("NARRATOR", "Voix Libre"),
            freeform("series-part", "4,5"),
            Mp4Builder.box("covr", Mp4Builder.box("data", dataHeader(14), ByteArray(2_000_000))),
            item("©day", 1, "2019-01-02".toByteArray())
        )
        val mvhd = Mp4Builder.fullBox(
            "mvhd",
            0,
            Mp4Builder.u32(0),
            Mp4Builder.u32(0),
            Mp4Builder.u32(1000),
            Mp4Builder.u32(36_000_000L),
            ByteArray(80)
        )
        val meta = Mp4Builder.fullBox("meta", 0, Mp4Builder.fullBox("hdlr", 0, ByteArray(20)), ilst)
        val moov = Mp4Builder.box("moov", mvhd, Mp4Builder.box("udta", meta))
        val source = CountingSource(
            listOf(
                CountingSource.Data(Mp4Builder.ftyp()),
                CountingSource.Data(Mp4Builder.u32(1) + "mdat".toByteArray() + Mp4Builder.u64(3_000_000_000L + 16)),
                CountingSource.Gap(3_000_000_000L),
                CountingSource.Data(moov)
            )
        )

        val r = probe.probe(source, "audio/x-m4b")

        assertEquals(10L * 3600 * 1000, r.durationMs)
        val t = r.tags
        assertEquals("Titre du livre", t.title)
        assertEquals("Album", t.album)
        assertEquals(5, t.track)
        assertEquals(9, t.trackTotal)
        assertEquals(1, t.disc)
        assertEquals(2, t.discTotal)
        assertEquals("Compositeur", t.composer)
        assertEquals("Long résumé", t.description)
        assertEquals("La Série", t.series)
        assertEquals(4.5, t.seriesIndex)
        assertEquals("Voix Libre", t.narrator)
        assertEquals(2019, t.year)
        assertEquals(2_000_000L, t.cover?.length)
        assertEquals("image/png", t.cover?.mimeType)
        assertTrue(source.bytesRead < 5_000, "${source.bytesRead} octets lus (couverture de 2 Mo, mdat de 3 Go)")
        assertTrue(source.readCalls < 100, "${source.readCalls} lectures")
    }

    @Test
    fun uneErreurDeSourceSePropageSansEtreAvaleeParLesChapitres() = runTest {
        val bytes = probeResource("cbr-info-v23.mp3")
        // Échoue à la N-ième lecture, pour toucher successivement l'étape des étiquettes puis celle des chapitres.
        for (failAt in 1..12) {
            var calls = 0
            val inner = CountingSource(bytes)
            val flaky = object : ByteRangeSource {
                override suspend fun size() = inner.size()

                override suspend fun read(offset: Long, length: Int): ByteArray {
                    if (++calls == failAt) throw IOException("réseau coupé")
                    return inner.read(offset, length)
                }
            }
            assertFailsWith<IOException>("échec à la lecture $failAt") { probe.probe(flaky, "audio/mpeg") }
        }
    }

    @Test
    fun leBudgetBorneLesOctetsLus() = runTest {
        val small = AudioProbe(tagBudgetBytes = 64, chapterBudgetBytes = 64)
        val source = CountingSource(probeResource("cbr-info-v23.mp3"))
        val r = small.probe(source, "audio/mpeg")
        assertTrue(source.bytesRead <= 128, "${source.bytesRead} octets lus")
        assertNotNull(r)
    }

    // --- constructeurs de boîtes iTunes ---------------------------------------------------------------

    private fun dataHeader(type: Int) = Mp4Builder.u32(type.toLong()) + Mp4Builder.u32(0)

    private fun item(key: String, type: Int, payload: ByteArray) =
        Mp4Builder.box(key, Mp4Builder.box("data", dataHeader(type), payload))

    private fun freeform(name: String, value: String) = Mp4Builder.box(
        "----",
        Mp4Builder.fullBox("mean", 0, "com.apple.iTunes".toByteArray()),
        Mp4Builder.fullBox("name", 0, name.toByteArray()),
        Mp4Builder.box("data", dataHeader(1), value.toByteArray())
    )
}
