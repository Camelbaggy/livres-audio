package io.github.camelbaggy.livresaudio.core.library.probe

import io.github.camelbaggy.livresaudio.core.drive.rest.DriveError
import io.github.camelbaggy.livresaudio.core.model.AccountId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

class DriveByteRangeSourceTest {
    private val content = ByteArray(200_000) { (it * 31 % 251).toByte() }

    /** Serves [content] honouring `Range` (206), or the whole file (200) when [honourRange] is false. */
    private class RangeServer(val content: ByteArray, val honourRange: Boolean = true) : Dispatcher() {
        val ranges = mutableListOf<String?>()
        val paths = mutableListOf<String>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            ranges += request.getHeader("Range")
            paths += request.path.orEmpty()
            val range = request.getHeader("Range")
            if (!honourRange || range == null) {
                return MockResponse().setResponseCode(200).setBody(Buffer().write(content))
            }
            val (a, b) = range.removePrefix("bytes=").split('-').map { it.toLong() }
            if (a >= content.size) return MockResponse().setResponseCode(416)
            val end = minOf(b, content.size - 1L)
            return MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $a-$end/${content.size}")
                .setBody(Buffer().write(content, a.toInt(), (end - a + 1).toInt()))
        }
    }

    private fun source(
        server: MockWebServer,
        size: Long? = content.size.toLong(),
        client: OkHttpClient = OkHttpClient()
    ) = DriveByteRangeSource(client, AccountId("acc"), "FILE1", size, server.url("/drive/v3/"))

    private inline fun withServer(dispatcher: Dispatcher, block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.dispatcher = dispatcher
        server.start()
        try {
            block(server)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun luParPlagesAvecEnteteRangeEtCacheDeBlocs() = runTest {
        val rs = RangeServer(content)
        withServer(rs) { server ->
            val src = source(server)
            assertContentEquals(content.copyOfRange(0, 100), src.read(0, 100))
            assertEquals(listOf<String?>("bytes=0-65535"), rs.ranges)
            assertEquals("/drive/v3/files/FILE1?alt=media", rs.paths.single())

            // Dans le bloc déjà chargé : aucune requête.
            assertContentEquals(content.copyOfRange(10, 60), src.read(10, 50))
            assertEquals(1, src.requestCount)

            // À cheval sur les blocs 0 (en cache) et 1 (manquant) : une seule requête, pour le bloc 1.
            assertContentEquals(content.copyOfRange(65_000, 66_000), src.read(65_000, 1_000))
            assertEquals(listOf<String?>("bytes=0-65535", "bytes=65536-131071"), rs.ranges)
        }
    }

    @Test
    fun blocsManquantsConsecutifsPartagentUneRequete() = runTest {
        val rs = RangeServer(content)
        withServer(rs) { server ->
            val src = source(server)
            assertContentEquals(content.copyOfRange(1_000, 150_000), src.read(1_000, 149_000))
            assertEquals(listOf<String?>("bytes=0-196607"), rs.ranges)
        }
    }

    @Test
    fun finDeFichierTronqueeEtLectureAuDelaVide() = runTest {
        val rs = RangeServer(content)
        withServer(rs) { server ->
            val src = source(server)
            assertContentEquals(content.copyOfRange(199_000, 200_000), src.read(199_000, 5_000))
            assertContentEquals(ByteArray(0), src.read(200_000, 10))
            assertContentEquals(ByteArray(0), src.read(0, 0))
            assertEquals(1, src.requestCount) // la lecture hors fichier ne coûte rien (taille connue)
            assertEquals(200_000L, src.size())
        }
    }

    @Test
    fun tailleInconnueUnBlocCourtVautFinDeFichier() = runTest {
        val rs = RangeServer(content)
        withServer(rs) { server ->
            val src = source(server, size = null)
            assertNull(src.size())
            assertContentEquals(content.copyOfRange(196_000, 200_000), src.read(196_000, 8_000))
            assertContentEquals(ByteArray(0), src.read(250_000, 10)) // 416 -> vide
        }
    }

    @Test
    fun serveurQuiIgnoreRangeDonneQuandMemeLesBonsOctets() = runTest {
        val rs = RangeServer(content, honourRange = false)
        withServer(rs) { server ->
            val src = source(server)
            assertContentEquals(content.copyOfRange(70_000, 70_100), src.read(70_000, 100))
        }
    }

    @Test
    fun erreursHttpTypees() = runTest {
        val cases = mapOf(
            404 to DriveError.NotFound("sans détail"),
            500 to DriveError.Server(500)
        )
        for ((code, expected) in cases) {
            withServer(object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(code)
            }) { server ->
                val e = assertFailsWith<DriveReadException> { source(server).read(0, 10) }
                assertEquals(expected, e.error, "HTTP $code")
            }
        }
        withServer(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(403)
                .setBody("""{"error":{"code":403,"message":"non","errors":[{"reason":"forbidden"}]}}""")
        }) { server ->
            val e = assertFailsWith<DriveReadException> { source(server).read(0, 10) }
            assertIs<DriveError.Forbidden>(e.error)
        }
    }

    @Test
    fun reseauCoupeDonneNetworkUnavailable() = runTest {
        val server = MockWebServer()
        server.start()
        val src = source(server)
        server.shutdown()
        val e = assertFailsWith<DriveReadException> { src.read(0, 10) }
        assertEquals(DriveError.NetworkUnavailable, e.error)
    }

    @Test
    fun leCacheEstBorneEtLesLecturesRestentCorrectes() = runTest {
        val rs = RangeServer(content)
        withServer(rs) { server ->
            val src = DriveByteRangeSource(
                OkHttpClient(),
                AccountId("acc"),
                "FILE1",
                content.size.toLong(),
                server.url("/drive/v3/"),
                blockSize = 1_000,
                maxCachedBlocks = 4
            )
            for (offset in listOf(0L, 50_000L, 120_000L, 500L, 199_500L)) {
                assertContentEquals(
                    content.copyOfRange(offset.toInt(), minOf(content.size, offset.toInt() + 2_500)),
                    src.read(offset, 2_500)
                )
            }
        }
    }
}
