package io.github.camelbaggy.livresaudio.core.library.probe

import io.github.camelbaggy.livresaudio.core.library.chapters.ByteReader
import io.github.camelbaggy.livresaudio.core.library.chapters.MalformedDataException
import io.github.camelbaggy.livresaudio.core.library.chapters.decodeTextWithBom
import io.github.camelbaggy.livresaudio.core.library.chapters.malformed
import io.github.camelbaggy.livresaudio.core.library.chapters.readFully
import io.github.camelbaggy.livresaudio.core.model.ByteRangeSource

/**
 * Duration (`moov/mvhd`) and iTunes-style tags (`moov/udta/meta/ilst`) of an MP4 / M4A / M4B file.
 *
 * The file is walked box header by box header (16-byte reads): `mdat`, `trak` and picture payloads are
 * jumped over by their size, so a few KiB are read whatever the size of the file. Text items are read
 * whole (at most [MAX_ITEM_BYTES]); the cover (`covr`) is only located. Any inconsistency stops the walk
 * and keeps what was already found.
 */
internal object Mp4Probe {
    class Result(var durationMs: Long? = null, var tags: AudioTags = AudioTags())

    private class Box(val type: String, val start: Long, val headerSize: Int, val end: Long) {
        val payload: Long get() = start + headerSize
    }

    suspend fun probe(source: ByteRangeSource): Result {
        val result = Result()
        try {
            walk(source, source.size(), result)
        } catch (_: ProbeBudgetException) {
            // budget spent: keep the partial result
        } catch (_: MalformedDataException) {
            // keep the partial result
        }
        return result
    }

    private suspend fun walk(source: ByteRangeSource, fileSize: Long?, out: Result) {
        var offset = 0L
        var seen = 0
        while (seen++ < MAX_BOXES) {
            val box = header(source, offset, fileSize ?: Long.MAX_VALUE, fileSize) ?: return
            if (box.type == "moov") {
                readMoov(source, box, out)
                return
            }
            offset = box.end
        }
    }

    /** Box header at [offset], or null at the end. A box overflowing [limit] is malformed (truncated file). */
    private suspend fun header(source: ByteRangeSource, offset: Long, limit: Long, fileSize: Long?): Box? {
        if (offset + 8 > limit) return null
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
        if (size > limit - offset) malformed("boîte débordante")
        return Box(type, offset, headerSize, offset + size)
    }

    private suspend fun children(source: ByteRangeSource, parent: Box, start: Long = parent.payload): List<Box> {
        val out = ArrayList<Box>()
        var offset = start
        while (out.size < MAX_BOXES) {
            val box = header(source, offset, parent.end, null) ?: break
            out += box
            offset = box.end
        }
        return out
    }

    private suspend fun readMoov(source: ByteRangeSource, moov: Box, out: Result) {
        for (child in children(source, moov)) {
            when (child.type) {
                "mvhd" -> out.durationMs = readMvhd(source, child)
                "udta" -> children(source, child).firstOrNull { it.type == "meta" }?.let { readMeta(source, it, out) }
                "meta" -> readMeta(source, child, out)
            }
        }
    }

    private suspend fun readMvhd(source: ByteRangeSource, box: Box): Long? {
        val r = ByteReader(source.readFully(box.payload, minOf(box.end - box.payload, 32L).toInt()))
        val version = r.u8()
        r.skip(3)
        val timescale: Long
        val duration: Long
        if (version == 1) {
            r.skip(16)
            timescale = r.u32()
            duration = r.u64()
        } else {
            r.skip(8)
            timescale = r.u32()
            duration = r.u32()
        }
        if (timescale <= 0 || duration <= 0 || duration == 0xFFFFFFFFL) return null
        return duration * 1000L / timescale
    }

    private suspend fun readMeta(source: ByteRangeSource, meta: Box, out: Result) {
        // ISO `meta` is a full box (4 bytes of version/flags); the QuickTime flavour is not.
        val probe = source.readFully(meta.payload, 8)
        val isoFullBox = !(probe.size >= 8 && String(probe, 4, 4, Charsets.ISO_8859_1) == "hdlr")
        val start = meta.payload + if (isoFullBox) 4 else 0
        val ilst = children(source, meta, start).firstOrNull { it.type == "ilst" } ?: return
        out.tags = readIlst(source, ilst)
    }

    private class Item(val key: String, val type: Int, val offset: Long, val length: Long, val bytes: ByteArray?)

    private suspend fun readIlst(source: ByteRangeSource, ilst: Box): AudioTags {
        var tags = AudioTags()
        for (item in children(source, ilst)) {
            val parsed = readItem(source, item) ?: continue
            tags = apply(tags, parsed)
        }
        return tags
    }

    /** Reads the first `data` atom of an item; `----` items give `<name>` as key. */
    private suspend fun readItem(source: ByteRangeSource, item: Box): Item? {
        val kids = children(source, item)
        val data = kids.firstOrNull { it.type == "data" } ?: return null
        val size = data.end - data.payload
        if (size < 8) return null
        val head = ByteReader(source.readFully(data.payload, 8))
        val type = (head.u32() and 0xFFFFFF).toInt()
        val valueOffset = data.payload + 8
        val valueLength = size - 8
        val key = if (item.type == "----") {
            val name = kids.firstOrNull { it.type == "name" } ?: return null
            if (name.end - name.payload !in 5..MAX_ITEM_BYTES) return null
            val bytes = source.readFully(name.payload + 4, (name.end - name.payload - 4).toInt())
            "----:" + String(bytes, Charsets.UTF_8).trim().uppercase()
        } else {
            item.type
        }
        val isCover = key == "covr"
        val bytes = if (isCover || valueLength > MAX_ITEM_BYTES) {
            null
        } else {
            source.readFully(valueOffset, valueLength.toInt())
        }
        return Item(key, type, valueOffset, valueLength, bytes)
    }

    private fun apply(tags: AudioTags, item: Item): AudioTags {
        val text = item.bytes?.let { decodeText(it, item.type) }?.takeIf { it.isNotBlank() }
        return when (item.key) {
            "©nam" -> tags.copy(title = text ?: tags.title)

            "©alb" -> tags.copy(album = text ?: tags.album)

            "©ART" -> tags.copy(artist = text ?: tags.artist)

            "aART" -> tags.copy(albumArtist = text ?: tags.albumArtist)

            "©wrt" -> tags.copy(composer = text ?: tags.composer)

            "©cmt" -> tags.copy(description = tags.description ?: text)

            "©day" -> tags.copy(year = text?.let(::yearOf) ?: tags.year)

            "desc" -> tags.copy(description = tags.description ?: text)

            "ldes" -> tags.copy(description = text ?: tags.description)

            "©mvn", "----:SERIES", "----:SERIES-NAME", "----:MVNM" -> tags.copy(series = text ?: tags.series)

            "mvin", "----:SERIES-PART", "----:SERIESPART", "----:SERIES_PART", "----:MVIN" ->
                tags.copy(seriesIndex = item.bytes?.let { numberOf(it, item.type, text) } ?: tags.seriesIndex)

            "----:NARRATOR", "----:NARRATED_BY" -> tags.copy(narrator = text ?: tags.narrator)

            "trkn" -> item.bytes?.let { pair(it) }?.let { (n, total) -> tags.copy(track = n, trackTotal = total) }
                ?: tags

            "disk" -> item.bytes?.let { pair(it) }?.let { (n, total) -> tags.copy(disc = n, discTotal = total) } ?: tags

            "covr" -> if (tags.cover == null && item.length > 0) {
                tags.copy(cover = EmbeddedCover(item.offset, item.length, coverMime(item.type)))
            } else {
                tags
            }

            else -> tags
        }
    }

    /** Data type 1 = UTF-8, 2 = UTF-16BE; anything else (integers...) is not text. */
    private fun decodeText(bytes: ByteArray, type: Int): String? = when (type) {
        1 -> String(bytes, Charsets.UTF_8).trim { it <= ' ' }
        2 -> String(bytes, Charsets.UTF_16BE).trim { it <= ' ' }
        0 -> decodeTextWithBom(bytes).trim { it <= ' ' }
        else -> null
    }

    private fun numberOf(bytes: ByteArray, type: Int, text: String?): Double? {
        if (type == TYPE_INTEGER && bytes.isNotEmpty() && bytes.size <= 8) {
            var v = 0L
            for (b in bytes) v = (v shl 8) or (b.toLong() and 0xFF)
            return v.toDouble()
        }
        return text?.replace(',', '.')?.trim()?.toDoubleOrNull()
    }

    /** `trkn` / `disk`: 2 reserved bytes, number, total (, 2 reserved bytes). */
    private fun pair(bytes: ByteArray): Pair<Int?, Int?>? {
        if (bytes.size < 6) return null
        val n = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        val total = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
        return (n.takeIf { it > 0 }) to (total.takeIf { it > 0 })
    }

    private fun coverMime(type: Int): String? = when (type) {
        TYPE_JPEG -> "image/jpeg"
        TYPE_PNG -> "image/png"
        TYPE_BMP -> "image/bmp"
        else -> null
    }

    private const val MAX_BOXES = 4_000
    private const val MAX_ITEM_BYTES = 64L * 1024
    private const val TYPE_JPEG = 13
    private const val TYPE_PNG = 14
    private const val TYPE_BMP = 27
    private const val TYPE_INTEGER = 21
}

/** First four digits of a date (`2012`, `2012-03-04`, `2012-03-04T10:00:00Z`) in a plausible range. */
internal fun yearOf(text: String): Int? {
    val m = YEAR_REGEX.find(text) ?: return null
    return m.value.toInt().takeIf { it in 1000..2999 }
}

private val YEAR_REGEX = Regex("""\d{4}""")
