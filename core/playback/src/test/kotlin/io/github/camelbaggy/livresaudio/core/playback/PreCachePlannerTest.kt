package io.github.camelbaggy.livresaudio.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PreCachePlannerTest {
    private val mib = 1024L * 1024

    /** 10 minutes at 8 bytes/ms (64 kb/s) = 4 800 000 bytes. */
    private fun f(n: Int, durationMs: Long? = 600_000, size: Long? = 4_800_000) =
        PlaybackFile("f$n", "md5-$n", durationMs, "Fichier $n", size)

    private val three = listOf(f(0), f(1), f(2))

    @Test
    fun `window inside the current file and across the following ones`() {
        // 30 min from 2:00 of file 0: the rest of file 0, files 1 and 2 entirely (the book ends there).
        val ranges = PreCachePlanner.plan(three, 0, 120_000, 30 * 60_000L)
        val margin = PreCachePlanner.START_MARGIN_BYTES
        assertEquals(
            listOf(
                PreCacheRange(0, 120_000 * 8 - margin, 4_800_000 - (120_000 * 8 - margin)),
                PreCacheRange(1, 0, 4_800_000),
                PreCacheRange(2, 0, 4_800_000)
            ),
            ranges
        )
    }

    @Test
    fun `the budget ends inside a file and the next one still gets its first 2 MiB`() {
        val ranges = PreCachePlanner.plan(three, 0, 0, 700_000)
        assertEquals(
            listOf(
                PreCacheRange(0, 0, 4_800_000),
                PreCacheRange(1, 0, 100_000 * 8),
                PreCacheRange(2, 0, 2 * mib)
            ),
            ranges
        )
    }

    @Test
    fun `a budget ending exactly at a file boundary still pre-caches the start of the next file`() {
        val ranges = PreCachePlanner.plan(three, 0, 0, 600_000)
        assertEquals(listOf(PreCacheRange(0, 0, 4_800_000), PreCacheRange(1, 0, 2 * mib)), ranges)
    }

    @Test
    fun `the first bytes of the next file are capped by its size`() {
        val files = listOf(f(0), f(1, size = 1_000_000))
        val ranges = PreCachePlanner.plan(files, 0, 590_000, 1_000)
        assertEquals(PreCacheRange(1, 0, 1_000_000), ranges.last())
    }

    @Test
    fun `last file of the book has no next file to prepare`() {
        val ranges = PreCachePlanner.plan(three, 2, 300_000, 60_000)
        assertEquals(listOf(2), ranges.map { it.fileIndex })
        assertEquals(60_000L * 8 + PreCachePlanner.START_MARGIN_BYTES, ranges.single().lengthBytes)
    }

    @Test
    fun `unknown sizes and durations assume 128 kb per second`() {
        val files = listOf(f(0, durationMs = null, size = null), f(1, durationMs = null, size = null))
        val ranges = PreCachePlanner.plan(files, 0, 0, 60_000)
        assertEquals(listOf(PreCacheRange(0, 0, 60_000 * 16), PreCacheRange(1, 0, 2 * mib)), ranges)
    }

    @Test
    fun `size without duration estimates the duration at the default rate`() {
        // 3 200 000 bytes at 16 B/ms = 200 s; a 5 min budget takes the whole file and moves on.
        val files = listOf(f(0, durationMs = null, size = 3_200_000), f(1))
        val ranges = PreCachePlanner.plan(files, 0, 0, 300_000)
        assertEquals(PreCacheRange(0, 0, 3_200_000), ranges[0])
        assertEquals(1, ranges[1].fileIndex)
        assertEquals(100_000L * 8, ranges[1].lengthBytes)
    }

    @Test
    fun `a position past the end of the file moves on to the next one`() {
        val ranges = PreCachePlanner.plan(three, 0, 700_000, 60_000)
        assertEquals(listOf(1), ranges.map { it.fileIndex }.distinct().take(1))
        assertEquals(PreCacheRange(1, 0, 60_000 * 8), ranges.first())
    }

    @Test
    fun `nothing to do when disabled or when the position is not in the book`() {
        assertEquals(emptyList(), PreCachePlanner.plan(three, 0, 0, 0))
        assertEquals(emptyList(), PreCachePlanner.plan(three, 7, 0, 60_000))
        assertEquals(emptyList(), PreCachePlanner.plan(emptyList(), 0, 0, 60_000))
    }

    @Test
    fun `an hour of audio is a bounded amount of data`() {
        val long = List(20) { f(it) }
        val total = PreCachePlanner.plan(long, 3, 0, 60 * 60_000L).sumOf { it.lengthBytes }
        assertEquals(6 * 4_800_000L + 2 * mib, total) // 6 files, plus the start of the 7th
    }
}

class CacheAheadTest {
    private fun f(n: Int, size: Long? = 4_800_000) = PlaybackFile("f$n", "md5-$n", 600_000, "F$n", size)
    private val files = listOf(f(0), f(1), f(2))

    /** A cache holding the given contiguous (start, end) byte ranges per file. */
    private fun cacheOf(vararg spans: Triple<Int, Long, Long>): (Int, Long, Long) -> Long = { index, start, max ->
        val span = spans.firstOrNull { it.first == index && start >= it.second && start < it.third }
        if (span == null) 0 else minOf(span.third - start, max)
    }

    @Test
    fun `counts the contiguous cached bytes of the current file`() {
        val cached = cacheOf(Triple(0, 0, 2_400_000))
        assertEquals(300_000L - 120_000, CacheAhead.availableMs(files, 0, 120_000, cached))
    }

    @Test
    fun `a fully cached file lets the count continue into the next one`() {
        val cached = cacheOf(Triple(0, 0, 4_800_000), Triple(1, 0, 800_000))
        assertEquals((600_000 - 120_000) + 100_000L, CacheAhead.availableMs(files, 0, 120_000, cached))
    }

    @Test
    fun `nothing cached means nothing available`() {
        assertEquals(0L, CacheAhead.availableMs(files, 1, 0, cacheOf()))
        assertEquals(0L, CacheAhead.availableMs(files, 9, 0, cacheOf()))
    }

    @Test
    fun `unknown size counts what is there without assuming completeness`() {
        val unknown = listOf(f(0, size = null), f(1))
        val cached = cacheOf(Triple(0, 0, 160_000), Triple(1, 0, 4_800_000))
        assertEquals(10_000L, CacheAhead.availableMs(unknown, 0, 0, cached)) // 160 000 B at 16 B/ms
    }
}
