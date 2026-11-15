package io.github.camelbaggy.livresaudio.core.playback

import kotlin.math.ceil

/** A byte range of one file of the book to keep in the stream cache. */
data class PreCacheRange(val fileIndex: Int, val startByte: Long, val lengthBytes: Long)

/** Audio time <-> bytes of a file. Pure. */
object ByteRates {
    /** 128 kb/s, the assumption when a file's size or duration is unknown (CLAUDE.md §5.4). */
    const val DEFAULT_BYTES_PER_MS = 16.0

    /** Average bytes per millisecond: size / known duration, else [DEFAULT_BYTES_PER_MS]. */
    fun bytesPerMs(file: PlaybackFile): Double {
        val size = file.sizeBytes
        val duration = file.durationMs
        return if (size != null && size > 0 && duration != null && duration > 0) {
            size.toDouble() / duration
        } else {
            DEFAULT_BYTES_PER_MS
        }
    }

    /** Known duration, else estimated from the size at the default rate; null when neither is known. */
    fun durationMs(file: PlaybackFile): Long? {
        file.durationMs?.takeIf { it > 0 }?.let { return it }
        return file.sizeBytes?.takeIf { it > 0 }?.let { (it / DEFAULT_BYTES_PER_MS).toLong() }
    }

    fun msToBytes(file: PlaybackFile, ms: Long): Long = (ms.coerceAtLeast(0) * bytesPerMs(file)).toLong()

    fun bytesToMs(file: PlaybackFile, bytes: Long): Long = (bytes.coerceAtLeast(0) / bytesPerMs(file)).toLong()
}

/**
 * Which byte ranges to keep cached ahead of the listening position (CLAUDE.md §5.4): [targetMs] of audio from the
 * position, across file boundaries, and always at least the first [MIN_NEXT_FILE_BYTES] of the file that follows
 * the last one touched (so the switch to the next file never waits). Pure.
 *
 * Ranges are in playback order. Already cached bytes are not subtracted here: the cache writer skips them.
 */
object PreCachePlanner {
    const val MIN_NEXT_FILE_BYTES = 2L * 1024 * 1024

    /**
     * The seek position of a time offset is approximate (constant-bitrate mapping): starting a little before it
     * makes sure the range covers what the player will actually read first.
     */
    const val START_MARGIN_BYTES = 512L * 1024

    /** `targetMs <= 0` means pre-caching is off: no range at all. */
    fun plan(files: List<PlaybackFile>, fileIndex: Int, positionMs: Long, targetMs: Long): List<PreCacheRange> {
        if (targetMs <= 0 || fileIndex !in files.indices) return emptyList()
        val ranges = mutableListOf<PreCacheRange>()
        var budgetMs = targetMs
        var index = fileIndex
        var fromMs = positionMs.coerceAtLeast(0)
        while (index < files.size && budgetMs > 0) {
            val file = files[index]
            val remainingMs = ByteRates.durationMs(file)?.let { (it - fromMs).coerceAtLeast(0) }
            // Unknown duration and size: the file is assumed to hold the whole remaining budget.
            val takeMs = remainingMs?.let { minOf(it, budgetMs) } ?: budgetMs
            val margin = if (index == fileIndex && fromMs > 0) START_MARGIN_BYTES else 0L
            val start = (ByteRates.msToBytes(file, fromMs) - margin).coerceAtLeast(0)
            val end = ceil((fromMs + takeMs) * ByteRates.bytesPerMs(file)).toLong()
            clampedRange(index, file, start, end)?.let(ranges::add)
            budgetMs -= takeMs
            index++
            fromMs = 0
            if (remainingMs == null) break
        }
        // The next file always has its first bytes cached, whatever the budget.
        val next = index
        if (next < files.size && ranges.none { it.fileIndex == next }) {
            val size = files[next].sizeBytes?.takeIf { it > 0 }
            ranges += PreCacheRange(next, 0, minOf(MIN_NEXT_FILE_BYTES, size ?: MIN_NEXT_FILE_BYTES))
        }
        return ranges
    }

    private fun clampedRange(index: Int, file: PlaybackFile, start: Long, end: Long): PreCacheRange? {
        val size = file.sizeBytes?.takeIf { it > 0 }
        val clampedEnd = if (size != null) minOf(end, size) else end
        val length = clampedEnd - start
        return if (length > 0) PreCacheRange(index, start, length) else null
    }
}

/**
 * How much audio the cache holds from the listening position: contiguous cached bytes of the current file,
 * continuing into the next files only while a file is entirely cached. Pure; the cache is read through
 * [cachedLength] (`Cache.getCachedLength` semantics: contiguous bytes from `startByte`, at most `maxBytes`).
 */
object CacheAhead {
    /** Never walks further than this (the pre-cache target is at most an hour). */
    const val MAX_WALK_MS = 2 * 60 * 60 * 1000L

    fun availableMs(
        files: List<PlaybackFile>,
        fileIndex: Int,
        positionMs: Long,
        cachedLength: (index: Int, startByte: Long, maxBytes: Long) -> Long
    ): Long {
        if (fileIndex !in files.indices) return 0
        var total = 0L
        var index = fileIndex
        var fromMs = positionMs.coerceAtLeast(0)
        while (index < files.size && total < MAX_WALK_MS) {
            val file = files[index]
            val size = file.sizeBytes?.takeIf { it > 0 }
            val start = ByteRates.msToBytes(file, fromMs)
            if (size == null) {
                // Unknown size: count what is there, we cannot tell whether the file is complete.
                total += ByteRates.bytesToMs(file, cachedLength(index, start, Long.MAX_VALUE).coerceAtLeast(0))
                break
            }
            if (start >= size) {
                index++
                fromMs = 0
                continue
            }
            val remaining = size - start
            val cached = cachedLength(index, start, remaining).coerceIn(0, remaining)
            total += ByteRates.bytesToMs(file, cached)
            if (cached < remaining) break
            index++
            fromMs = 0
        }
        return total
    }
}
