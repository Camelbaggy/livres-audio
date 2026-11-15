package io.github.camelbaggy.livresaudio.core.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A Drive stand-in: every file is `SIZE` bytes of a position-dependent pattern; counts what was served. */
@UnstableApi
private class PatternUpstream(private val sleepPerReadMs: Long = 0, private val failWith: IOException? = null) :
    DataSource {
    var opens = 0
    var bytesServed = 0L
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L

    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long {
        failWith?.let { throw it }
        opens++
        uri = dataSpec.uri
        position = dataSpec.position
        val available = (SIZE - position).coerceAtLeast(0)
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(dataSpec.length, available)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (sleepPerReadMs > 0) Thread.sleep(sleepPerReadMs)
        val count = minOf(length.toLong(), remaining, 1_024).toInt()
        for (i in 0 until count) buffer[offset + i] = byteAt(position + i)
        position += count
        remaining -= count
        bytesServed += count
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() = Unit

    companion object {
        const val SIZE = 100_000L

        fun byteAt(position: Long): Byte = (position * 31 % 251).toByte()
    }
}

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class CacheWriterSinkTest {
    private lateinit var dir: File
    private lateinit var cache: SimpleCache
    private val ref = DriveMediaRef("acc", "FILE1", "md5-1")

    @BeforeTest
    fun setUp() {
        dir = File.createTempFile("precache", "").also {
            it.delete()
            it.mkdirs()
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        cache = SimpleCache(dir, NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
    }

    @AfterTest
    fun tearDown() {
        cache.release()
        dir.deleteRecursively()
    }

    private fun sink(upstream: DataSource) =
        CacheWriterSink(DriveDataSourceFactory.cacheFactory(cache, { upstream }), Dispatchers.Unconfined)

    @Test
    fun `the pre-cacher writes under the key of the player's media item`() {
        val book =
            PlaybackBook("b", "acc", "T", "A", null, listOf(PlaybackFile("FILE1", "md5-1", 1000, "f")), emptyList())
        val item = BookPlaylist.build(book).single()
        val upstream = PatternUpstream()
        runBlocking { sink(upstream).cache(ref, 10_000, 20_000) }

        val playerKey = item.localConfiguration!!.customCacheKey
        assertEquals("drive:FILE1:md5-1", playerKey)
        assertEquals(20_000L, cache.getCachedLength(playerKey!!, 10_000, 20_000))
        // The factory the player uses derives the same key from the pre-cacher's DataSpec, and from the player's own.
        assertEquals(playerKey, DriveDataSourceFactory.cacheKeyFactory.buildCacheKey(PlaybackDataSpecs.wholeFile(ref)))
        val playerSpec = DataSpec.Builder().setUri(item.localConfiguration!!.uri).setKey(playerKey).build()
        assertEquals(playerKey, DriveDataSourceFactory.cacheKeyFactory.buildCacheKey(playerSpec))
        assertEquals(20_000L, upstream.bytesServed)
    }

    @Test
    fun `the player reads what the pre-cacher wrote without touching the network`() {
        runBlocking { sink(PatternUpstream()).cache(ref, 10_000, 20_000) }

        val book =
            PlaybackBook("b", "acc", "T", "A", null, listOf(PlaybackFile("FILE1", "md5-1", 1000, "f")), emptyList())
        val item = BookPlaylist.build(book).single()
        val offline = PatternUpstream(failWith = IOException("hors ligne"))
        val reader = DriveDataSourceFactory.cacheFactory(cache, { offline }).createDataSource()
        // A read inside the cached range, built the way the player's loader builds it (item URI + custom key).
        val spec = DataSpec.Builder()
            .setUri(item.localConfiguration!!.uri)
            .setKey(item.localConfiguration!!.customCacheKey)
            .setPosition(12_000)
            .setLength(5_000)
            .build()
        val out = ByteArray(5_000)
        reader.open(spec)
        var read = 0
        while (read < out.size) {
            val n = reader.read(out, read, out.size - read)
            if (n == C.RESULT_END_OF_INPUT) break
            read += n
        }
        reader.close()
        assertEquals(5_000, read)
        assertContentEquals(ByteArray(5_000) { PatternUpstream.byteAt(12_000L + it) }, out)
    }

    @Test
    fun `already cached bytes are not downloaded again`() {
        val upstream = PatternUpstream()
        val s = sink(upstream)
        runBlocking {
            s.cache(ref, 0, 30_000)
            s.cache(ref, 10_000, 30_000) // overlaps 20 000 cached bytes
        }
        assertEquals(40_000L, upstream.bytesServed)
        assertEquals(40_000L, cache.getCachedLength("drive:FILE1:md5-1", 0, 40_000))
    }

    @Test
    fun `a network failure surfaces as an IOException and leaves the cache consistent`() {
        val failing = PatternUpstream(failWith = IOException("hors ligne"))
        assertFailsWith<IOException> { runBlocking { sink(failing).cache(ref, 0, 10_000) } }
        assertEquals(0L, cache.getCachedBytes("drive:FILE1:md5-1", 0, C.LENGTH_UNSET.toLong()))
    }

    @Test
    fun `cancelling the coroutine stops a download in progress, keeping what was written`() {
        val slow = PatternUpstream(sleepPerReadMs = 5)
        val s = CacheWriterSink(DriveDataSourceFactory.cacheFactory(cache, { slow }), Dispatchers.IO)
        runBlocking {
            val job = launch(Dispatchers.Default) {
                try {
                    s.cache(ref, 0, PatternUpstream.SIZE)
                } catch (_: CancellationException) {
                    // expected
                }
            }
            kotlinx.coroutines.delay(150)
            job.cancel()
            withTimeout(5_000) { job.join() }
        }
        assertTrue(slow.bytesServed in 1 until PatternUpstream.SIZE, "served ${slow.bytesServed}")
    }
}
