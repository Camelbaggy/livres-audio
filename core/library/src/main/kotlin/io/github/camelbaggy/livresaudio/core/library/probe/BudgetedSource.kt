package io.github.camelbaggy.livresaudio.core.library.probe

import io.github.camelbaggy.livresaudio.core.model.ByteRangeSource
import java.io.IOException

/** The read budget of one probe is spent: the parser concerned gives up (partial result). */
internal class ProbeBudgetException(message: String) : RuntimeException(message)

/**
 * Wraps a source with a bound on the number of reads and bytes, and remembers the first I/O failure of the
 * underlying source. The latter matters because the chapter parsers swallow every exception: the probe
 * checks [ioFailure] afterwards so that a network error is never mistaken for "no chapters".
 */
internal class BudgetedSource(
    private val inner: ByteRangeSource,
    private val maxBytes: Long,
    private val maxReads: Int
) : ByteRangeSource {
    var bytesRead = 0L
        private set
    var reads = 0
        private set
    var ioFailure: IOException? = null
        private set

    override suspend fun size(): Long? = guarded { inner.size() }

    override suspend fun read(offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        if (++reads > maxReads) throw ProbeBudgetException("trop de lectures")
        if (length > maxBytes - bytesRead) throw ProbeBudgetException("budget d'octets épuisé")
        val data = guarded { inner.read(offset, length) }
        bytesRead += data.size
        return data
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        if (ioFailure == null) ioFailure = e
        throw e
    }
}

/**
 * Presents the bytes of `[maskStart, maskEnd)` as zeros WITHOUT reading them. Used so that the MP4 chapter
 * parser, which reads the whole `moov`, does not download the cover picture stored inside it: the parser only
 * follows box sizes there, never the picture bytes.
 */
internal class MaskedSource(
    private val inner: ByteRangeSource,
    private val maskStart: Long,
    private val maskEnd: Long
) : ByteRangeSource {
    override suspend fun size(): Long? = inner.size()

    override suspend fun read(offset: Long, length: Int): ByteArray {
        val end = offset + length
        if (length <= 0 || end <= maskStart || offset >= maskEnd) return inner.read(offset, length)
        val before = (maskStart - offset).coerceIn(0L, length.toLong()).toInt()
        val out = java.io.ByteArrayOutputStream(length)
        if (before > 0) {
            val part = inner.read(offset, before)
            out.write(part)
            if (part.size < before) return out.toByteArray() // end of file
        }
        val maskedFrom = maxOf(offset, maskStart)
        val maskedTo = minOf(end, maskEnd)
        val size = inner.size()
        val available = if (size == null) maskedTo else minOf(maskedTo, size)
        if (available > maskedFrom) out.write(ByteArray((available - maskedFrom).toInt()))
        if (size != null && size < maskedTo) return out.toByteArray()
        if (end > maskEnd) out.write(inner.read(maskEnd, (end - maskEnd).toInt()))
        return out.toByteArray()
    }
}
