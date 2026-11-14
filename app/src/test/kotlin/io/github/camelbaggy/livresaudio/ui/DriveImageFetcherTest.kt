package io.github.camelbaggy.livresaudio.ui

import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.fetch.SourceFetchResult
import io.github.camelbaggy.livresaudio.core.data.repo.driveImageUri
import io.github.camelbaggy.livresaudio.core.model.AccountId
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.Path.Companion.toOkioPath

class DriveImageFetcherTest {
    private val ref = DriveImageRef(AccountId("acc-1"), "file-9")
    private val bytes = ByteArray(300) { it.toByte() }

    @Test
    fun parsesDriveImageUrisOnly() {
        assertEquals(ref, parseDriveImageUri("drive-image://acc-1/file-9"))
        assertEquals(ref, parseDriveImageUri(driveImageUri(AccountId("acc-1"), "file-9")))
        assertNull(parseDriveImageUri("drive-image://acc-1"))
        assertNull(parseDriveImageUri("drive-image://acc-1/"))
        assertNull(parseDriveImageUri("drive-image://acc-1/a/b"))
        assertNull(parseDriveImageUri("https://example.org/a"))
        assertNull(parseDriveImageUri("drive://acc/file"))
    }

    private fun cache(): DiskCache {
        val dir = File.createTempFile("coil", "").also {
            it.delete()
            it.mkdirs()
            it.deleteOnExit()
        }
        return DiskCache.Builder().directory(dir.toOkioPath()).maxSizeBytes(1_000_000).build()
    }

    private fun fetcher(server: MockWebServer, cache: DiskCache?, maxBytes: Long = MAX_DRIVE_IMAGE_BYTES) =
        DriveImageFetcher(
            ref = ref,
            client = OkHttpClient(),
            baseUrl = server.url("/drive/v3/"),
            diskCache = cache,
            readCache = true,
            writeCache = true,
            maxBytes = maxBytes
        )

    private fun SourceFetchResult.readAll(): ByteArray = source.source().use { it.readByteArray() }

    @Test
    fun downloadsWithMediaUrlThenServesFromDiskCacheWithoutNetwork() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(Buffer().write(bytes)).setHeader("Content-Type", "image/jpeg")
        )
        server.start()
        val cache = cache()

        val first = assertIs<SourceFetchResult>(fetcher(server, cache).fetch())
        assertEquals(DataSource.NETWORK, first.dataSource)
        assertEquals("image/jpeg", first.mimeType)
        assertEquals(bytes.toList(), first.readAll().toList())
        val request = server.takeRequest()
        assertEquals("/drive/v3/files/file-9?alt=media", request.path)

        // Second display (offline, e.g. airplane mode): the server is gone, the cache answers.
        server.shutdown()
        val second = assertIs<SourceFetchResult>(fetcher(server, cache).fetch())
        assertEquals(DataSource.DISK, second.dataSource)
        assertEquals(bytes.toList(), second.readAll().toList())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun worksWithoutDiskCache() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        server.start()
        val result = assertIs<SourceFetchResult>(fetcher(server, null).fetch())
        assertEquals(bytes.toList(), result.readAll().toList())
        server.shutdown()
    }

    @Test
    fun refusesImagesAboveTheLimitAndHttpErrors() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(5_000))))
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        server.start()
        val cache = cache()

        assertFailsWith<IOException> { fetcher(server, cache, maxBytes = 1_000).fetch() }
        assertFailsWith<IOException> { fetcher(server, cache).fetch() }
        assertNull(cache.openSnapshot("drive-image:acc-1:file-9"))
        server.shutdown()
    }
}
