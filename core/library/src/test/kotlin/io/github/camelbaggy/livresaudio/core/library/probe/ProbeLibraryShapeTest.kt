package io.github.camelbaggy.livresaudio.core.library.probe

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.camelbaggy.livresaudio.core.data.db.LibraryDatabase
import io.github.camelbaggy.livresaudio.core.data.repo.RoomLibraryRepository
import io.github.camelbaggy.livresaudio.core.library.chapters.CountingSource
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.BookAnchor
import io.github.camelbaggy.livresaudio.core.model.BookOrigin
import io.github.camelbaggy.livresaudio.core.model.ByteRangeSource
import io.github.camelbaggy.livresaudio.core.model.DiscoveredBook
import io.github.camelbaggy.livresaudio.core.model.DriveItem
import io.github.camelbaggy.livresaudio.core.model.ParsedName
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The library of the real user: 15 shared books of 80 MP3 and 230 personal single-file M4B whose `moov` weighs
 * 6 MiB (sample tables), read through the real [DriveByteRangeSource] (64 KiB blocks, HTTP ranges) over a fake
 * HTTP layer that counts requests and remembers which byte ranges were asked for.
 */
@RunWith(RobolectricTestRunner::class)
class ProbeLibraryShapeTest {
    private lateinit var db: LibraryDatabase
    private lateinit var library: RoomLibraryRepository
    private val covers = object : CoverStore {
        override fun save(bookId: String, bytes: ByteArray, mimeType: String?) = "file:///covers/$bookId.jpg"
    }

    private class Virtual(val segments: List<CountingSource.Segment>) {
        val size = segments.sumOf { it.length }
        val source = CountingSource(segments)
    }

    /** Requests made per Drive file id: `[start, endInclusive]`. */
    private val ranges = ConcurrentHashMap<String, MutableList<LongRange>>()
    private val files = ConcurrentHashMap<String, Virtual>()

    private val client = OkHttpClient.Builder().addInterceptor(
        Interceptor { chain ->
            val request = chain.request()
            val id = request.url.pathSegments.last()
            val file = files.getValue(id)
            val (from, to) = request.header("Range")!!.removePrefix("bytes=").split('-').map { it.toLong() }
            ranges.getOrPut(id) { CopyOnWriteArrayList() } += from..to
            val bytes = runBlocking { file.source.read(from, (to - from + 1).toInt()) }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(206).message("Partial Content")
                .body(bytes.toResponseBody()).build()
        }
    ).build()

    private val sources = object : ProbeSourceFactory {
        val opened = AtomicInteger()

        override fun open(accountId: String, driveFileId: String, sizeBytes: Long?): ByteRangeSource {
            opened.incrementAndGet()
            return DriveByteRangeSource(client, AccountId(accountId), driveFileId, sizeBytes)
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        library = RoomLibraryRepository(db, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** MP3 with a tag holding an 80 KiB cover, then 30 MiB of audio (virtual). */
    private fun bigMp3(): Virtual {
        val head = probeResource("cbr-info-v23.mp3")
        return Virtual(listOf(CountingSource.Data(head), CountingSource.Gap(30L * 1024 * 1024)))
    }

    private class BigM4b(val file: Virtual, val trakStart: Long, val trakEnd: Long)

    /** `complet.m4b` with its small `trak` replaced by a 6 MiB one, `mdat` inflated to 300 MiB: moov at the end. */
    private fun bigM4b(): BigM4b {
        val real = probeResource("complet.m4b")
        fun u32(at: Int) = ((real[at].toLong() and 0xFF) shl 24) or ((real[at + 1].toLong() and 0xFF) shl 16) or
            ((real[at + 2].toLong() and 0xFF) shl 8) or (real[at + 3].toLong() and 0xFF)

        fun box(size: Long, type: String) =
            byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()) +
                type.toByteArray(Charsets.ISO_8859_1)
        // complet.m4b: ftyp(28) free(8) mdat(1770) moov(...) = mvhd, trak, udta
        val mdatAt = 36
        val moovAt = mdatAt + u32(mdatAt).toInt()
        val mvhdAt = moovAt + 8
        val trakAt = mvhdAt + u32(mvhdAt).toInt()
        val udtaAt = trakAt + u32(trakAt).toInt()
        val udtaSize = u32(udtaAt).toInt()
        val mdatSize = 300L * 1024 * 1024
        val trakSize = 6L * 1024 * 1024
        val mvhd = real.copyOfRange(mvhdAt, trakAt)
        val udta = real.copyOfRange(udtaAt, udtaAt + udtaSize)
        val moovSize = 8 + mvhd.size + trakSize + udta.size
        val prefix = real.copyOfRange(0, mdatAt) + box(mdatSize, "mdat")
        val trakStart = prefix.size + (mdatSize - 8) + 8 + mvhd.size
        val segments = listOf(
            CountingSource.Data(prefix),
            CountingSource.Gap(mdatSize - 8),
            CountingSource.Data(box(moovSize, "moov") + mvhd),
            CountingSource.Data(box(trakSize, "trak")),
            CountingSource.Gap(trakSize - 8),
            CountingSource.Data(udta)
        )
        return BigM4b(Virtual(segments), trakStart, trakStart + trakSize)
    }

    private fun item(id: String, name: String, mime: String, size: Long) =
        DriveItem(id, name, mime, "folder", sizeBytes = size, md5 = "md5-$id")

    private suspend fun add(folder: String, account: String, vararg items: DriveItem): String =
        library.upsertDiscoveredBook(
            DiscoveredBook(BookAnchor.Folder(folder), "root", items.toList(), emptyList(), ParsedName(), emptyList()),
            AccountId(account),
            if (account == "commun") BookOrigin.SHARED else BookOrigin.PERSONAL,
            1_000
        )

    @Test
    fun theWholeShowcaseCostsAFewRequestsPerBookAndNeverReadsASampleTable() = runTest {
        val mp3 = bigMp3()
        val m4b = bigM4b()
        val ids = ArrayList<String>()
        for (b in 1..15) {
            val items = (1..80).map { n ->
                val id = "c$b-$n"
                files[id] = mp3
                item(id, "%02d.mp3".format(n), "audio/mpeg", mp3.size)
            }
            ids += add("commun-$b", "commun", *items.toTypedArray())
        }
        for (b in 1..230) {
            val id = "p$b"
            files[id] = m4b.file
            ids += add("perso-$b", "perso", item(id, "livre.m4b", "audio/mp4", m4b.file.size))
        }
        val total = ids.size
        // Showcase only: the time budget is spent as soon as every book has been opened once.
        val timed = ProbeRunner(
            RoomProbeStore(db, Dispatchers.Unconfined),
            sources,
            AudioProbe(),
            covers,
            monotonicMs = { if (sources.opened.get() >= total) 1_000_000L else 0L }
        )

        val report = timed.run(timeBudgetMs = 1_000)

        assertEquals(total, report.booksProbed)
        assertEquals(0, report.filesProbed)
        assertTrue(report.timeUp)
        // Every cover and title is there.
        for (id in ids) {
            val book = assertNotNull(library.getBook(id)).book
            assertNotNull(book.coverUri, "couverture de $id")
            assertNotNull(book.title, "titre de $id")
        }
        // Cost per book.
        val perBook = ranges.values.map { it.size }
        val perM4b = (1..230).map { ranges.getValue("p$it").size }
        val perMp3 = (1..15).map { ranges.getValue("c$it-1").size }
        println(
            "PROBE-SHAPE showcase: books=$total requests=${report.showcaseRequests} bytes=${report.bytes} " +
                "mp3 max/avg=${perMp3.max()}/${perMp3.average()} m4b max/avg=${perM4b.max()}/${perM4b.average()}"
        )
        assertEquals(total, ranges.size, "only the first file of each book is touched")
        assertEquals(report.showcaseRequests, perBook.sum())
        assertTrue(perMp3.max() <= 4, "MP3: $perMp3")
        assertTrue(perM4b.max() <= 5, "M4B: $perM4b")
        // No sample table: nothing of the 6 MiB `trak` is requested, except near its two ends.
        val margin = 128L * 1024
        val forbidden = (m4b.trakStart + margin)..(m4b.trakEnd - margin)
        for (b in 1..230) {
            for (r in ranges.getValue("p$b")) {
                assertTrue(
                    r.last < forbidden.first || r.first > forbidden.last,
                    "plage $r dans la table d'échantillons"
                )
            }
        }
        // And only a few hundred KiB per book.
        assertTrue(report.bytes < total * 600L * 1024, "octets: ${report.bytes}")
    }

    @Test
    fun theDetailsPhaseGivesDurationsAndChaptersAfterTheShowcase() = runTest {
        val m4b = bigM4b()
        val ids = (1..3).map { b ->
            files["p$b"] = m4b.file
            add("perso-$b", "perso", item("p$b", "livre.m4b", "audio/mp4", m4b.file.size))
        }
        val runner = ProbeRunner(RoomProbeStore(db, Dispatchers.Unconfined), sources, AudioProbe(), covers)

        val report = runner.run()

        assertEquals(3, report.booksProbed)
        assertEquals(3, report.filesProbed)
        assertTrue(!report.hasRemainingWork)
        for (id in ids) {
            val b = assertNotNull(library.getBook(id))
            assertTrue(b.files.single().probed)
            assertNotNull(b.book.totalDurationMs)
        }
    }
}
