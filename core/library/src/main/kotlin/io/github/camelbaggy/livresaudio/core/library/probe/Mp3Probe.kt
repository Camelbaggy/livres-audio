package io.github.camelbaggy.livresaudio.core.library.probe

import io.github.camelbaggy.livresaudio.core.library.chapters.MalformedDataException
import io.github.camelbaggy.livresaudio.core.library.chapters.decodeTextWithBom
import io.github.camelbaggy.livresaudio.core.library.chapters.readFully
import io.github.camelbaggy.livresaudio.core.model.ByteRangeSource

/**
 * Tags (ID3v2.2 / 2.3 / 2.4) and duration of an MP3 file.
 *
 * Only the start of the file is read: the ID3v2 tag is walked frame by frame (headers, then the text frames
 * we know; the `APIC` picture is only located, never read), then the first MPEG frame is looked for to find
 * the Xing / Info / VBRI header (exact frame count). Without it the duration is estimated from the constant
 * bit rate and the size (the last 128 bytes are read once, to subtract an ID3v1 tag).
 */
internal object Mp3Probe {
    class Result(var durationMs: Long? = null, var tags: AudioTags = AudioTags())

    suspend fun probe(source: ByteRangeSource): Result {
        val result = Result()
        val fileSize = source.size()
        var audioStart = 0L
        try {
            val head = source.readFully(0, ID3_HEADER)
            if (head.size == ID3_HEADER && isId3(head)) {
                val tag = Id3Header.parse(head)
                if (tag != null) {
                    audioStart = tag.end
                    try {
                        result.tags = readTags(source, tag)
                    } catch (_: ProbeBudgetException) {
                        // budget spent: keep the partial result
                    } catch (_: MalformedDataException) {
                        // tags unreadable: duration may still be found
                    }
                }
            }
            result.durationMs = Mpeg.duration(source, audioStart, fileSize)
        } catch (_: ProbeBudgetException) {
            // budget spent: keep the partial result
        } catch (_: MalformedDataException) {
            // keep the partial result
        }
        return result
    }

    private fun isId3(h: ByteArray) =
        h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte()

    // --- ID3v2 ---------------------------------------------------------------------------------------

    private class Id3Header(val major: Int, val flags: Int, val size: Int) {
        /** First byte after the tag (footer included). */
        val end: Long get() = ID3_HEADER.toLong() + size +
            (if (major == 4 && flags and 0x10 != 0) ID3_HEADER.toLong() else 0L)

        companion object {
            fun parse(h: ByteArray): Id3Header? {
                val major = h[3].toInt() and 0xFF
                if (major !in 2..4) return null
                val size = synchsafe(h, 6)
                if (size < 0) return null
                return Id3Header(major, h[5].toInt() and 0xFF, size)
            }
        }
    }

    private fun interface Bytes {
        suspend fun get(pos: Long, len: Int): ByteArray?
    }

    private suspend fun readTags(source: ByteRangeSource, tag: Id3Header): AudioTags {
        val tagStart = ID3_HEADER.toLong()
        val tagEnd = tagStart + tag.size
        if (tag.major < 4 && tag.flags and 0x80 != 0) {
            // Tag-level unsynchronisation: frame sizes only make sense after decoding, so read it whole.
            if (tag.size > MAX_UNSYNC_TAG_BYTES) return AudioTags()
            val raw = source.readFully(tagStart, tag.size)
            if (raw.size < tag.size) return AudioTags()
            val data = removeUnsynchronisation(raw)
            val bytes = Bytes { pos, len ->
                if (pos < 0 || pos + len > data.size) null else data.copyOfRange(pos.toInt(), (pos + len).toInt())
            }
            return walkFrames(bytes, tag.major, 0L, data.size.toLong(), absoluteBase = null)
        }
        var pos = tagStart
        if (tag.flags and 0x40 != 0 && tag.major >= 3) {
            val b = source.readFully(pos, 4)
            if (b.size < 4) return AudioTags()
            val skip: Long = if (tag.major == 4) synchsafe(b, 0).toLong() else be32(b, 0) + 4
            if (skip < 6) return AudioTags()
            pos += skip
        }
        val bytes = Bytes { p, len -> source.readFully(p, len).takeIf { it.size == len } }
        return walkFrames(bytes, tag.major, pos, tagEnd, absoluteBase = 0L)
    }

    /** [absoluteBase] null = in-memory data (the file position of a picture is then unknown: no cover). */
    private suspend fun walkFrames(bytes: Bytes, major: Int, start: Long, end: Long, absoluteBase: Long?): AudioTags {
        val headerSize = if (major == 2) 6 else 10
        var tags = AudioTags()
        var coverIsFront = false
        var pos = start
        var count = 0
        while (pos + headerSize <= end && count++ < MAX_FRAMES) {
            val h = bytes.get(pos, headerSize) ?: break
            if (h[0].toInt() == 0) break // padding
            val rawId = String(h, 0, if (major == 2) 3 else 4, Charsets.ISO_8859_1)
            val size = when (major) {
                2 -> ((h[3].toInt() and 0xFF) shl 16) or ((h[4].toInt() and 0xFF) shl 8) or (h[5].toInt() and 0xFF)
                4 -> synchsafe(h, 4)
                else -> be32(h, 4).let { if (it > Int.MAX_VALUE) -1 else it.toInt() }
            }
            val flags = if (major == 2) 0 else ((h[8].toInt() and 0xFF) shl 8) or (h[9].toInt() and 0xFF)
            val bodyStart = pos + headerSize
            if (size < 0 || bodyStart + size > end) break
            pos = bodyStart + size
            if (isCompressedOrEncrypted(major, flags)) continue
            val id = if (major == 2) ID_V22_TO_V23[rawId] ?: continue else rawId
            when (id) {
                "APIC" -> {
                    if (absoluteBase == null || size < 4) continue
                    val body = bytes.get(bodyStart, minOf(size, APIC_HEAD_BYTES)) ?: continue
                    val pic = parseApicHead(body, major == 2) ?: continue
                    val offset = absoluteBase + bodyStart + pic.imageStart
                    val length = size - pic.imageStart.toLong()
                    val front = pic.pictureType == FRONT_COVER
                    if (length > 0 && (tags.cover == null || (front && !coverIsFront))) {
                        coverIsFront = front
                        tags = tags.copy(cover = EmbeddedCover(offset, length, pic.mimeType))
                    }
                }

                in TEXT_IDS, "TXXX", "COMM" -> {
                    if (size < 2 || size > MAX_TEXT_FRAME_BYTES) continue
                    var body = bytes.get(bodyStart, size) ?: continue
                    body = frameBody(major, flags, body) ?: continue
                    tags = applyText(tags, id, body)
                }
            }
        }
        return tags
    }

    private fun isCompressedOrEncrypted(major: Int, flags: Int): Boolean = when (major) {
        3 -> flags and 0x00C0 != 0
        4 -> flags and 0x000C != 0
        else -> false
    }

    /** Removes the per-frame extras of ID3v2.3/2.4 (grouping byte, data length indicator, unsynchronisation). */
    private fun frameBody(major: Int, flags: Int, body: ByteArray): ByteArray? {
        if (major < 3) return body
        var from = 0
        var data = body
        if (major == 3) {
            if (flags and 0x0020 != 0) from += 1
        } else {
            if (flags and 0x0040 != 0) from += 1
            if (flags and 0x0001 != 0) from += 4
            if (flags and 0x0002 != 0) data = removeUnsynchronisation(body)
        }
        return if (from >= data.size) {
            null
        } else if (from == 0) {
            data
        } else {
            data.copyOfRange(from, data.size)
        }
    }

    private fun applyText(tags: AudioTags, id: String, body: ByteArray): AudioTags {
        if (id == "TXXX") {
            val (name, value) = parseUserText(body) ?: return tags
            return applyUserText(tags, name.uppercase(), value)
        }
        if (id == "COMM") {
            val comment = parseComment(body) ?: return tags
            return if (tags.description == null) tags.copy(description = comment) else tags
        }
        val values = decodeStrings(body)
        val first = values.firstOrNull() ?: return tags
        val joined = values.joinToString(", ")
        return when (id) {
            "TIT2" -> tags.copy(title = first)
            "TALB" -> tags.copy(album = first)
            "TPE1" -> tags.copy(artist = joined)
            "TPE2" -> tags.copy(albumArtist = joined)
            "TCOM" -> tags.copy(composer = joined)
            "TRCK" -> numberPair(first).let { (n, total) -> tags.copy(track = n, trackTotal = total) }
            "TPOS" -> numberPair(first).let { (n, total) -> tags.copy(disc = n, discTotal = total) }
            "TYER", "TDRC", "TDRL" -> if (tags.year == null) tags.copy(year = yearOf(first)) else tags
            "MVNM" -> tags.copy(series = first)
            "MVIN" -> tags.copy(seriesIndex = first.replace(',', '.').toDoubleOrNull() ?: tags.seriesIndex)
            else -> tags
        }
    }

    private fun applyUserText(tags: AudioTags, name: String, value: String): AudioTags = when (name) {
        "SERIES", "SERIES-NAME", "SERIE", "MVNM" -> tags.copy(series = value)

        "SERIES-PART", "SERIESPART", "SERIES_PART", "PART", "MVIN" ->
            tags.copy(seriesIndex = value.replace(',', '.').toDoubleOrNull() ?: tags.seriesIndex)

        "NARRATOR", "NARRATED_BY", "NARRATEUR" -> tags.copy(narrator = value)

        "COMMENT", "DESCRIPTION" -> if (tags.description == null) tags.copy(description = value) else tags

        else -> tags
    }

    private fun numberPair(text: String): Pair<Int?, Int?> {
        val parts = text.split('/', limit = 2)
        return parts[0].trim().toIntOrNull()?.takeIf { it > 0 } to
            parts.getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    }

    // --- Text decoding ---------------------------------------------------------------------------

    private fun widthOf(encoding: Int) = if (encoding == 1 || encoding == 2) 2 else 1

    private fun decodePiece(encoding: Int, bytes: ByteArray): String = when (encoding) {
        1 -> if (bytes.size >= 2 && (bytes[0] == 0xFF.toByte() || bytes[0] == 0xFE.toByte())) {
            decodeTextWithBom(bytes)
        } else {
            String(bytes, Charsets.UTF_16LE)
        }

        2 -> String(bytes, Charsets.UTF_16BE)

        3 -> String(bytes, Charsets.UTF_8)

        else -> String(bytes, Charsets.ISO_8859_1)
    }.trim { it <= ' ' }

    /** Index of the first terminator (aligned for UTF-16) at or after [from], or -1. */
    private fun terminator(bytes: ByteArray, from: Int, encoding: Int): Int {
        val w = widthOf(encoding)
        var i = from
        while (i + w <= bytes.size) {
            if (bytes[i].toInt() == 0 && (w == 1 || bytes[i + 1].toInt() == 0)) return i
            i += w
        }
        return -1
    }

    /** Strings of a text frame body (encoding byte first), empty ones dropped. */
    internal fun decodeStrings(body: ByteArray): List<String> {
        if (body.isEmpty()) return emptyList()
        val encoding = body[0].toInt() and 0xFF
        val w = widthOf(encoding)
        val out = ArrayList<String>()
        var from = 1
        while (from < body.size) {
            val t = terminator(body, from, encoding)
            val to = if (t < 0) body.size else t
            if (to > from) out += decodePiece(encoding, body.copyOfRange(from, to))
            if (t < 0) break
            from = t + w
        }
        return out.filter { it.isNotEmpty() }
    }

    private fun parseUserText(body: ByteArray): Pair<String, String>? {
        val encoding = body[0].toInt() and 0xFF
        val t = terminator(body, 1, encoding)
        if (t < 0) return null
        val valueStart = t + widthOf(encoding)
        if (valueStart >= body.size) return null
        val name = decodePiece(encoding, body.copyOfRange(1, t))
        val rest = body.copyOfRange(valueStart, body.size)
        val value = decodeStrings(byteArrayOf(body[0]) + rest).firstOrNull() ?: return null
        return name to value
    }

    /** `COMM`: encoding, language (3), short description, text. */
    private fun parseComment(body: ByteArray): String? {
        if (body.size < 5) return null
        val encoding = body[0].toInt() and 0xFF
        val t = terminator(body, 4, encoding)
        if (t < 0) return null
        val textStart = t + widthOf(encoding)
        if (textStart >= body.size) return null
        val text = decodePiece(encoding, body.copyOfRange(textStart, body.size)).trimEnd('\u0000')
        return text.takeIf { it.isNotBlank() }
    }

    private class ApicHead(val imageStart: Int, val mimeType: String?, val pictureType: Int)

    private fun parseApicHead(body: ByteArray, v22: Boolean): ApicHead? {
        if (body.size < 6) return null
        val encoding = body[0].toInt() and 0xFF
        var pos = 1
        val mime: String?
        if (v22) {
            val fmt = String(body, 1, 3, Charsets.ISO_8859_1).uppercase()
            mime = when (fmt) {
                "JPG" -> "image/jpeg"
                "PNG" -> "image/png"
                else -> null
            }
            pos = 4
        } else {
            var z = pos
            while (z < body.size && body[z].toInt() != 0) z++
            if (z >= body.size) return null
            mime = String(body, pos, z - pos, Charsets.ISO_8859_1).lowercase().takeIf { it.startsWith("image/") }
                ?.let { if (it == "image/jpg") "image/jpeg" else it }
            pos = z + 1
        }
        if (pos >= body.size) return null
        val type = body[pos].toInt() and 0xFF
        pos++
        val t = terminator(body, pos, encoding)
        if (t < 0) return null
        return ApicHead(t + widthOf(encoding), mime, type)
    }

    private val ID_V22_TO_V23 = mapOf(
        "TT2" to "TIT2", "TAL" to "TALB", "TP1" to "TPE1", "TP2" to "TPE2", "TCM" to "TCOM",
        "TRK" to "TRCK", "TPA" to "TPOS", "TYE" to "TYER", "PIC" to "APIC", "COM" to "COMM", "TXX" to "TXXX"
    )

    private val TEXT_IDS =
        setOf("TIT2", "TALB", "TPE1", "TPE2", "TCOM", "TRCK", "TPOS", "TYER", "TDRC", "TDRL", "MVNM", "MVIN")

    private const val ID3_HEADER = 10
    private const val MAX_FRAMES = 500
    private const val MAX_TEXT_FRAME_BYTES = 64 * 1024
    private const val APIC_HEAD_BYTES = 512
    private const val MAX_UNSYNC_TAG_BYTES = 256 * 1024
    private const val FRONT_COVER = 3

    private fun synchsafe(b: ByteArray, at: Int): Int {
        var v = 0
        for (i in 0 until 4) {
            val x = b[at + i].toInt() and 0xFF
            if (x and 0x80 != 0) return -1
            v = (v shl 7) or x
        }
        return v
    }

    private fun be32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    /** `FF 00` -> `FF`. */
    private fun removeUnsynchronisation(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            val b = data[i]
            out.write(b.toInt())
            if (b == 0xFF.toByte() && i + 1 < data.size && data[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }
}

/** MPEG audio frame headers: duration from the Xing / Info / VBRI header, else from the constant bit rate. */
internal object Mpeg {
    private val BITRATES_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BITRATES_V2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val RATES_V1 = intArrayOf(44100, 48000, 32000)
    private val RATES_V2 = intArrayOf(22050, 24000, 16000)
    private val RATES_V25 = intArrayOf(11025, 12000, 8000)

    class Frame(
        val isV1: Boolean,
        val bitrate: Int,
        val sampleRate: Int,
        val mono: Boolean,
        val crc: Boolean,
        val length: Int
    ) {
        val samples: Int get() = if (isV1) 1152 else 576
        val sideInfo: Int get() = if (isV1) (if (mono) 17 else 32) else (if (mono) 9 else 17)
    }

    /** Layer III header at [at] in [b], or null if it is not a valid one. */
    fun parseHeader(b: ByteArray, at: Int): Frame? {
        if (at + 4 > b.size) return null
        val b0 = b[at].toInt() and 0xFF
        val b1 = b[at + 1].toInt() and 0xFF
        val b2 = b[at + 2].toInt() and 0xFF
        val b3 = b[at + 3].toInt() and 0xFF
        if (b0 != 0xFF || b1 and 0xE0 != 0xE0) return null
        val version = (b1 shr 3) and 3 // 0 = 2.5, 2 = 2, 3 = 1
        if (version == 1) return null
        if ((b1 shr 1) and 3 != 1) return null // Layer III only
        val brIndex = b2 shr 4
        val srIndex = (b2 shr 2) and 3
        if (brIndex == 0 || brIndex == 15 || srIndex == 3) return null
        val isV1 = version == 3
        val bitrate = (if (isV1) BITRATES_V1_L3 else BITRATES_V2_L3)[brIndex] * 1000
        val rate = (
            if (isV1) {
                RATES_V1
            } else if (version == 2) {
                RATES_V2
            } else {
                RATES_V25
            }
            )[srIndex]
        val padding = (b2 shr 1) and 1
        val length = (if (isV1) 144 else 72) * bitrate / rate + padding
        return Frame(isV1, bitrate, rate, (b3 shr 6) == 3, (b1 and 1) == 0, length)
    }

    suspend fun duration(source: ByteRangeSource, audioStart: Long, fileSize: Long?): Long? {
        if (fileSize != null && audioStart >= fileSize) return null
        val window = source.readFully(audioStart, SCAN_WINDOW)
        val at = findFirstFrame(window) ?: return null
        val frame = parseHeader(window, at) ?: return null
        val frames = frameCount(window, at, frame)
        if (frames != null && frames > 0) return frames * frame.samples * 1000L / frame.sampleRate
        if (fileSize == null) return null
        var bytes = fileSize - audioStart - at
        if (fileSize >= ID3V1_SIZE && hasId3v1(source, fileSize)) bytes -= ID3V1_SIZE
        if (bytes <= 0) return null
        return bytes * 8 * 1000 / frame.bitrate
    }

    private suspend fun hasId3v1(source: ByteRangeSource, fileSize: Long): Boolean {
        val tail = source.readFully(fileSize - ID3V1_SIZE, 3)
        return tail.size == 3 && String(tail, Charsets.ISO_8859_1) == "TAG"
    }

    /** First header that is followed (when visible) by another valid header one frame further. */
    private fun findFirstFrame(window: ByteArray): Int? {
        var i = 0
        while (i + 4 <= window.size) {
            val frame = parseHeader(window, i)
            if (frame != null) {
                val next = i + frame.length
                if (next + 4 > window.size || parseHeader(window, next) != null) return i
            }
            i++
        }
        return null
    }

    private fun frameCount(w: ByteArray, at: Int, frame: Frame): Long? {
        val base = at + 4 + frame.sideInfo
        for (offset in listOf(base, if (frame.crc) base + 2 else base)) {
            if (tagAt(w, offset, "Xing") || tagAt(w, offset, "Info")) {
                if (offset + 12 > w.size) return null
                val flags = be32(w, offset + 4)
                return if (flags and 1L != 0L) be32(w, offset + 8) else null
            }
        }
        val vbri = at + 4 + 32
        if (tagAt(w, vbri, "VBRI") && vbri + 18 <= w.size) return be32(w, vbri + 14)
        return null
    }

    private fun tagAt(w: ByteArray, at: Int, tag: String): Boolean =
        at + 4 <= w.size && String(w, at, 4, Charsets.ISO_8859_1) == tag

    private fun be32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private const val SCAN_WINDOW = 32 * 1024
    private const val ID3V1_SIZE = 128L
}
