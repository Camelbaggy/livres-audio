package io.github.camelbaggy.livresaudio.core.library.chapters

import io.github.camelbaggy.livresaudio.core.model.ByteRangeSource
import io.github.camelbaggy.livresaudio.core.model.EmbeddedChapter

/**
 * Chapitres d'un fichier MP4 / M4B / M4A : piste de chapitres QuickTime (`tref/chap` → piste texte),
 * à défaut atome Nero `chpl` (`moov/udta/chpl`). Ne lit que les en-têtes de boîtes de premier
 * niveau (`mdat` est sauté par sa taille), le `moov` entier et les quelques échantillons texte.
 * Toute incohérence donne une liste vide.
 */
class Mp4ChapterParser {
    suspend fun parse(source: ByteRangeSource): List<EmbeddedChapter> = try {
        locateMoov(source)?.let { parseMoov(source, it) }.orEmpty()
    } catch (_: MalformedDataException) {
        emptyList()
    } catch (_: IndexOutOfBoundsException) {
        emptyList()
    }

    private suspend fun locateMoov(source: ByteRangeSource): ByteArray? {
        val fileSize = source.size()
        var offset = 0L
        repeat(MAX_TOP_LEVEL_BOXES) {
            if (fileSize != null && offset + 8 > fileSize) return null
            val head = source.readFully(offset, 16)
            if (head.size < 8) return null
            val r = ByteReader(head)
            var size = r.u32()
            val type = r.fourCc()
            var headerSize = 8
            if (size == 1L) {
                if (head.size < 16) return null
                size = r.u64()
                headerSize = 16
            } else if (size == 0L) {
                size = (fileSize ?: return null) - offset
            }
            if (size < headerSize) malformed("taille de boîte < en-tête")
            if (fileSize != null && offset + size > fileSize) return null // tronqué
            if (type == "moov") {
                val len = size - headerSize
                if (len > MAX_MOOV_BYTES) malformed("moov trop gros")
                val payload = source.readFully(offset + headerSize, len.toInt())
                return if (payload.size.toLong() == len) payload else null
            }
            if (size > Long.MAX_VALUE - offset) malformed("débordement")
            offset += size
        }
        return null
    }

    private class Box(val type: String, val start: Int, val end: Int)

    private class Context {
        var boxCount = 0
    }

    private fun children(data: ByteArray, start: Int, end: Int, ctx: Context): List<Box> {
        val out = ArrayList<Box>()
        var pos = start
        while (end - pos >= 8) {
            if (++ctx.boxCount > MAX_BOXES) malformed("trop de boîtes")
            val r = ByteReader(data, pos, end)
            var size = r.u32()
            val type = r.fourCc()
            var header = 8
            if (size == 1L) {
                size = r.u64()
                header = 16
            } else if (size == 0L) {
                size = (end - pos).toLong()
            }
            if (size < header || size > end - pos) malformed("taille de boîte incohérente")
            out += Box(type, pos + header, pos + size.toInt())
            pos += size.toInt()
        }
        return out
    }

    private fun List<Box>.find(type: String) = firstOrNull { it.type == type }

    private fun Box.kids(data: ByteArray, ctx: Context) = children(data, start, end, ctx)

    private suspend fun parseMoov(source: ByteRangeSource, moov: ByteArray): List<EmbeddedChapter> {
        val ctx = Context()
        val top = children(moov, 0, moov.size, ctx)
        val traks = top.filter { it.type == "trak" }

        // Identifiant de la piste de chapitres désignée par une piste via tref/chap.
        var chapterTrackId: Long? = null
        val trackIds = HashMap<Long, Box>()
        for (trak in traks) {
            val kids = trak.kids(moov, ctx)
            kids.find("tkhd")?.let { trackIds[readTrackId(moov, it)] = trak }
            val chap = kids.find("tref")?.kids(moov, ctx)?.find("chap")
            if (chapterTrackId == null && chap != null && chap.end - chap.start >= 4) {
                chapterTrackId = ByteReader(moov, chap.start, chap.end).u32()
            }
        }
        val quickTime = chapterTrackId
            ?.let { trackIds[it] }
            ?.let { readQuickTimeChapters(source, moov, it, ctx) }
            .orEmpty()
        if (quickTime.isNotEmpty()) return quickTime

        val udta = top.find("udta")?.kids(moov, ctx)
        val chpl = udta?.find("chpl")
        return chpl?.let { normalizeChapters(parseChpl(moov, it.start, it.end)) }.orEmpty()
    }

    private fun readTrackId(data: ByteArray, tkhd: Box): Long {
        val r = ByteReader(data, tkhd.start, tkhd.end)
        val version = r.u8()
        r.skip(3)
        r.skip(if (version == 1) 16 else 8)
        return r.u32()
    }

    // --- chpl (Nero) -------------------------------------------------------------------------

    /**
     * Version 1 : 4 octets réservés puis compteur sur 1 octet (ffmpeg, mp4v2) ; version 0 : compteur
     * directement. Les deux dispositions sont essayées, la bonne est celle qui consomme exactement la boîte.
     */
    internal fun parseChpl(data: ByteArray, start: Int, end: Int): List<EmbeddedChapter> {
        val version = ByteReader(data, start, end).u8()
        val skips = if (version >= 1) listOf(4, 0) else listOf(0, 4)
        for (skip in skips) {
            chplLayout(data, start + 4, end, skip)?.let { return it }
        }
        return emptyList()
    }

    private fun chplLayout(data: ByteArray, start: Int, end: Int, skip: Int): List<EmbeddedChapter>? {
        if (end - start < skip + 1) return null
        val r = ByteReader(data, start, end)
        r.skip(skip.toLong())
        val count = r.u8()
        val out = ArrayList<EmbeddedChapter>(count)
        repeat(count) {
            if (r.remaining < 9) return null
            val start100ns = r.u64()
            val len = r.u8()
            if (r.remaining < len) return null
            out += EmbeddedChapter(String(r.bytes(len), Charsets.UTF_8), start100ns / TICKS_100NS_PER_MS, null)
        }
        return if (r.remaining == 0 && count > 0) out else null
    }

    // --- Piste de chapitres QuickTime --------------------------------------------------------

    private suspend fun readQuickTimeChapters(
        source: ByteRangeSource,
        moov: ByteArray,
        trak: Box,
        ctx: Context
    ): List<EmbeddedChapter> {
        val mdia = trak.kids(moov, ctx).find("mdia") ?: return emptyList()
        val mdiaKids = mdia.kids(moov, ctx)
        val timescale = mdiaKids.find("mdhd")?.let { readTimescale(moov, it) } ?: return emptyList()
        val stbl = mdiaKids.find("minf")?.kids(moov, ctx)?.find("stbl")?.kids(moov, ctx) ?: return emptyList()
        val durations = stbl.find("stts")?.let { readStts(moov, it) } ?: return emptyList()
        val sizes = stbl.find("stsz")?.let { readStsz(moov, it) } ?: return emptyList()
        val stsc = stbl.find("stsc")?.let { readStsc(moov, it) } ?: return emptyList()
        val chunkOffsets = stbl.find("stco")?.let { readStco(moov, it, wide = false) }
            ?: stbl.find("co64")?.let { readStco(moov, it, wide = true) }
            ?: return emptyList()

        val count = minOf(sizes.size, durations.size)
        if (count == 0) return emptyList()
        val offsets = sampleOffsets(stsc, chunkOffsets, sizes, count)
        val texts = readSamples(source, offsets, sizes, count)

        val starts = LongArray(count + 1)
        for (i in 0 until count) starts[i + 1] = starts[i] + durations[i]
        fun ms(ticks: Long) = ticks * 1000 / timescale
        val raw = (0 until count).map { i -> EmbeddedChapter(texts[i], ms(starts[i]), ms(starts[i + 1])) }
        return normalizeChapters(raw)
    }

    private fun readTimescale(data: ByteArray, mdhd: Box): Long {
        val r = ByteReader(data, mdhd.start, mdhd.end)
        val version = r.u8()
        r.skip(3)
        r.skip(if (version == 1) 16 else 8)
        return r.u32().also { if (it == 0L) malformed("timescale nul") }
    }

    /** Durée de chaque échantillon, en unités du timescale. */
    private fun readStts(data: ByteArray, box: Box): LongArray {
        val r = ByteReader(data, box.start, box.end)
        r.skip(4)
        val entries = r.u32()
        if (entries * 8 > r.remaining) malformed("stts tronquée")
        val out = ArrayList<Long>()
        repeat(entries.toInt()) {
            val n = r.u32()
            val delta = r.u32()
            if (out.size + n > MAX_CHAPTER_SAMPLES) malformed("trop d'échantillons")
            repeat(n.toInt()) { out += delta }
        }
        return out.toLongArray()
    }

    private fun readStsz(data: ByteArray, box: Box): LongArray {
        val r = ByteReader(data, box.start, box.end)
        r.skip(4)
        val fixed = r.u32()
        val count = r.u32()
        if (count > MAX_CHAPTER_SAMPLES) malformed("trop d'échantillons")
        if (fixed != 0L) return LongArray(count.toInt()) { fixed }
        if (count * 4 > r.remaining) malformed("stsz tronquée")
        return LongArray(count.toInt()) { r.u32() }
    }

    private class StscEntry(val firstChunk: Long, val samplesPerChunk: Long)

    private fun readStsc(data: ByteArray, box: Box): List<StscEntry> {
        val r = ByteReader(data, box.start, box.end)
        r.skip(4)
        val entries = r.u32()
        if (entries * 12 > r.remaining) malformed("stsc tronquée")
        return List(entries.toInt()) {
            val first = r.u32()
            val per = r.u32()
            r.skip(4)
            StscEntry(first, per)
        }
    }

    private fun readStco(data: ByteArray, box: Box, wide: Boolean): LongArray {
        val r = ByteReader(data, box.start, box.end)
        r.skip(4)
        val n = r.u32()
        if (n * (if (wide) 8 else 4) > r.remaining) malformed("stco tronquée")
        return LongArray(n.toInt()) { if (wide) r.u64() else r.u32() }
    }

    private fun sampleOffsets(stsc: List<StscEntry>, chunks: LongArray, sizes: LongArray, count: Int): LongArray {
        if (stsc.isEmpty() || stsc.first().firstChunk != 1L) malformed("stsc invalide")
        val out = LongArray(count)
        var sample = 0
        var entry = 0
        for (chunkIdx in chunks.indices) {
            if (sample >= count) break
            val chunkNo = chunkIdx + 1L
            while (entry + 1 < stsc.size && stsc[entry + 1].firstChunk <= chunkNo) entry++
            var offset = chunks[chunkIdx]
            var k = 0L
            while (k < stsc[entry].samplesPerChunk && sample < count) {
                out[sample] = offset
                offset += sizes[sample]
                sample++
                k++
            }
        }
        if (sample < count) malformed("moins de chunks que d'échantillons")
        return out
    }

    /** Lit les échantillons texte : une seule plage si ils sont proches, sinon un par un. */
    private suspend fun readSamples(
        source: ByteRangeSource,
        offsets: LongArray,
        sizes: LongArray,
        count: Int
    ): List<String?> {
        for (i in 0 until count) if (sizes[i] > MAX_SAMPLE_BYTES) malformed("échantillon texte trop gros")
        val lo = offsets.take(count).min()
        val hi = (0 until count).maxOf { offsets[it] + sizes[it] }
        val span = hi - lo
        val block = if (span in 1..MAX_SAMPLE_SPAN) source.readFully(lo, span.toInt()) else null
        return (0 until count).map { i ->
            val size = sizes[i].toInt()
            val bytes = if (block != null) {
                val from = (offsets[i] - lo).toInt()
                if (from + size > block.size) malformed("échantillon tronqué")
                block.copyOfRange(from, from + size)
            } else {
                source.readFully(offsets[i], size).also { if (it.size < size) malformed("échantillon tronqué") }
            }
            decodeTextSample(bytes)
        }
    }

    internal fun decodeTextSample(sample: ByteArray): String? {
        if (sample.size < 2) return null
        val len = ((sample[0].toInt() and 0xFF) shl 8) or (sample[1].toInt() and 0xFF)
        val text = sample.copyOfRange(2, minOf(2 + len, sample.size))
        return decodeTextWithBom(text)
    }

    private companion object {
        const val MAX_TOP_LEVEL_BOXES = 10_000
        const val MAX_BOXES = 200_000
        const val MAX_MOOV_BYTES = 64L * 1024 * 1024
        const val MAX_CHAPTER_SAMPLES = 20_000L
        const val MAX_SAMPLE_BYTES = 64L * 1024
        const val MAX_SAMPLE_SPAN = 512L * 1024
        const val TICKS_100NS_PER_MS = 10_000L
    }
}
