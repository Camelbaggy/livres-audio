package io.github.camelbaggy.livresaudio.core.playback

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PreCacherTest {
    private fun f(n: Int) = PlaybackFile("f$n", "md5-$n", 600_000, "Fichier $n", 4_800_000)

    /** Ten files of 10 minutes, 4 800 000 bytes each. */
    private val tenFiles = PlaybackBook("book", "acc", "T", "A", null, List(10) { f(it) }, emptyList())

    private class Call(val fileId: String, val start: Long, val length: Long)

    private class RecordingSink(private val workMs: Long = 0, private val failWith: IOException? = null) :
        PreCacheSink {
        val calls = mutableListOf<Call>()

        override suspend fun cache(ref: DriveMediaRef, startByte: Long, lengthBytes: Long) {
            if (failWith != null) throw failWith
            if (workMs > 0) delay(workMs)
            calls += Call(ref.fileId, startByte, lengthBytes)
        }
    }

    private class Where(var fileIndex: Int = 0, var positionMs: Long = 0, var playing: Boolean = true)

    private fun TestScope.cacher(
        sink: PreCacheSink,
        network: FakeNetwork = FakeNetwork(),
        where: Where = Where(),
        starved: () -> Boolean = { false },
        chunk: Long = 8L * 1024 * 1024,
        policy: PreCachePolicy = DefaultPreCachePolicy
    ) = PreCacher(
        scope = backgroundScope,
        sink = sink,
        network = network,
        input = { PreCacheInput(tenFiles, where.fileIndex, where.positionMs) },
        playerNeedsData = starved,
        isPlaying = { where.playing },
        policy = policy,
        chunkBytes = chunk
    ).also { it.start() }

    @Test
    fun `plans from the position and caches in order, one range after the other`() = runTest {
        val sink = RecordingSink()
        val c = cacher(sink, chunk = 4_800_000, where = Where(0, 0, playing = false))
        c.trigger()
        advanceTimeBy(PreCacher.DEBOUNCE_MS + 1)
        runCurrent()
        // Wi-Fi: 60 min = 6 files, then 2 MiB of the 7th.
        assertEquals(List(7) { "f$it" }, sink.calls.map { it.fileId })
        assertEquals(6 * 4_800_000L + 2L * 1024 * 1024, sink.calls.sumOf { it.length })
        assertEquals(1, c.completedPasses)
    }

    @Test
    fun `mobile data caches half as much as wi-fi`() = runTest {
        val wifi = RecordingSink()
        val mobile = RecordingSink()
        cacher(wifi, FakeNetwork(metered = false), Where(playing = false)).trigger()
        cacher(mobile, FakeNetwork(metered = true), Where(playing = false)).trigger()
        advanceTimeBy(PreCacher.DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(3 * 4_800_000L + 2L * 1024 * 1024, mobile.calls.sumOf { it.length })
        assertEquals(2 * mobile.calls.take(3).sumOf { it.length } + 2L * 1024 * 1024, wifi.calls.sumOf { it.length })
    }

    @Test
    fun `a policy of zero disables pre-caching`() = runTest {
        val sink = RecordingSink()
        cacher(sink, policy = PreCachePolicy { 0 }, where = Where(playing = false)).trigger()
        advanceTimeBy(PreCacher.DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(0, sink.calls.size)
    }

    @Test
    fun `ranges are split in chunks`() = runTest {
        val sink = RecordingSink()
        val where = Where(playing = false)
        cacher(sink, where = where, chunk = 1_000_000, policy = PreCachePolicy { 600_000 }).trigger()
        advanceTimeBy(PreCacher.DEBOUNCE_MS + 1)
        runCurrent()
        // 4 800 000 bytes of file 0 in 5 chunks (1 000 000 max), then 2 MiB of file 1 in 3 chunks.
        assertEquals(listOf(1_000_000L, 1_000_000, 1_000_000, 1_000_000, 800_000), sink.calls.take(5).map { it.length })
        assertEquals(listOf(0L, 1_000_000, 2_000_000, 3_000_000, 4_000_000), sink.calls.take(5).map { it.start })
        assertTrue(sink.calls.drop(5).all { it.fileId == "f1" })
        assertEquals(2L * 1024 * 1024, sink.calls.drop(5).sumOf { it.length })
    }

    @Test
    fun `a trigger cancels the pass in progress and plans again from the new position`() = runTest {
        val sink = RecordingSink(workMs = 1_000)
        val where = Where(0, 0)
        val c = cacher(sink, where = where, chunk = 1_000_000)
        c.trigger()
        advanceTimeBy(PreCacher.DEBOUNCE_MS + 2_500) // two chunks of file 0 done, third in progress
        runCurrent()
        assertEquals(listOf("f0", "f0"), sink.calls.map { it.fileId })
        // The user seeks to file 5: the old pass must stop, the new one starts from there.
        where.fileIndex = 5
        where.positionMs = 0
        c.trigger()
        advanceTimeBy(PreCacher.DEBOUNCE_MS + 1_500)
        runCurrent()
        assertEquals(listOf("f0", "f0", "f5"), sink.calls.map { it.fileId })
        assertEquals(0L, sink.calls.last().start)
    }

    @Test
    fun `a burst of triggers makes a single pass`() = runTest {
        val sink = RecordingSink()
        val c = cacher(sink, where = Where(playing = false), policy = PreCachePolicy { 600_000 })
        repeat(5) { c.trigger() }
        advanceTimeBy(PreCacher.DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(1, c.completedPasses)
    }

    @Test
    fun `nothing is downloaded while the player is starved`() = runTest {
        val sink = RecordingSink()
        var starved = true
        val c = cacher(sink, starved = { starved }, where = Where(playing = false))
        c.trigger()
        advanceTimeBy(PreCacher.DEBOUNCE_MS + 10_000)
        runCurrent()
        assertEquals(0, sink.calls.size)
        starved = false
        advanceTimeBy(PreCacher.YIELD_POLL_MS + 1)
        runCurrent()
        assertTrue(sink.calls.isNotEmpty())
    }

    @Test
    fun `offline nothing runs, back online a trigger resumes the work`() = runTest {
        val sink = RecordingSink()
        val network = FakeNetwork(online = false)
        val c = cacher(sink, network, Where(playing = false), policy = PreCachePolicy { 600_000 })
        c.trigger()
        advanceTimeBy(PreCacher.DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(0, sink.calls.size)
        network.set(true)
        c.trigger()
        advanceTimeBy(PreCacher.DEBOUNCE_MS + 1)
        runCurrent()
        assertTrue(sink.calls.isNotEmpty())
    }

    @Test
    fun `an I-O failure ends the pass quietly and the next trigger tries again`() = runTest {
        val failing = RecordingSink(failWith = IOException("réseau"))
        val c = cacher(failing, where = Where(playing = false))
        c.trigger()
        advanceTimeBy(PreCacher.DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(1, c.failedPasses)
        assertEquals(0, c.completedPasses)
    }

    @Test
    fun `while playing the window is refreshed, once paused a pass is enough`() = runTest {
        val sink = RecordingSink()
        val where = Where(playing = true)
        val c = cacher(sink, where = where, policy = PreCachePolicy { 600_000 })
        c.trigger()
        advanceTimeBy(PreCacher.DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(1, c.completedPasses)
        advanceTimeBy(PreCacher.REFRESH_MS)
        runCurrent()
        assertEquals(2, c.completedPasses)
        where.playing = false
        advanceTimeBy(PreCacher.REFRESH_MS)
        runCurrent()
        assertEquals(3, c.completedPasses) // the last one, started while playing
        advanceTimeBy(10 * PreCacher.REFRESH_MS)
        runCurrent()
        assertEquals(3, c.completedPasses)
    }
}
