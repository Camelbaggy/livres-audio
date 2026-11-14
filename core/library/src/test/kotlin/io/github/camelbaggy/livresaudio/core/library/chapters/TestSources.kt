package io.github.camelbaggy.livresaudio.core.library.chapters

import io.github.camelbaggy.livresaudio.core.model.ByteRangeSource
import java.io.ByteArrayOutputStream

/** Source de test qui compte les octets rendus et le nombre de lectures. */
open class CountingSource(private val segments: List<Segment>) : ByteRangeSource {
    sealed interface Segment {
        val length: Long
    }

    class Data(val bytes: ByteArray) : Segment {
        override val length: Long get() = bytes.size.toLong()
    }

    /** Zone virtuelle de zéros (par ex. un `mdat` de plusieurs Go) qui ne consomme pas de mémoire. */
    class Gap(override val length: Long) : Segment

    constructor(bytes: ByteArray) : this(listOf(Data(bytes)))

    var bytesRead = 0L
        private set
    var readCalls = 0
        private set

    private val total = segments.sumOf { it.length }

    override suspend fun size(): Long = total

    override suspend fun read(offset: Long, length: Int): ByteArray {
        readCalls++
        if (offset < 0 || offset >= total || length <= 0) return ByteArray(0)
        val wanted = minOf(length.toLong(), total - offset).toInt()
        val out = ByteArrayOutputStream(wanted)
        var segStart = 0L
        for (seg in segments) {
            val segEnd = segStart + seg.length
            val from = maxOf(offset, segStart)
            val to = minOf(offset + wanted, segEnd)
            if (from < to) {
                when (seg) {
                    is Data -> out.write(seg.bytes, (from - segStart).toInt(), (to - from).toInt())
                    is Gap -> out.write(ByteArray((to - from).toInt()))
                }
            }
            segStart = segEnd
        }
        bytesRead += out.size()
        return out.toByteArray()
    }
}

/** Source dont la taille est inconnue. */
class UnknownSizeSource(private val bytes: ByteArray) : ByteRangeSource {
    override suspend fun size(): Long? = null

    override suspend fun read(offset: Long, length: Int): ByteArray {
        if (offset >= bytes.size) return ByteArray(0)
        return bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + length).toInt())
    }
}

internal fun resource(name: String): ByteArray =
    CountingSource::class.java.getResourceAsStream("/chapters/$name")?.use { it.readBytes() }
        ?: error("ressource de test absente : $name")

/** Constructeur minimal de boîtes MP4 pour les cas limites. */
object Mp4Builder {
    fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    fun u64(v: Long) = u32(v ushr 32) + u32(v and 0xFFFFFFFFL)

    fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    fun box(type: String, vararg parts: ByteArray): ByteArray {
        val payload = parts.fold(ByteArray(0)) { a, b -> a + b }
        return u32(payload.size + 8L) + type.toByteArray(Charsets.ISO_8859_1) + payload
    }

    fun fullBox(type: String, version: Int, vararg parts: ByteArray) =
        box(type, byteArrayOf(version.toByte(), 0, 0, 0), *parts)

    fun ftyp() = box("ftyp", "M4B ".toByteArray(), u32(0), "M4B isom".toByteArray())

    fun chpl(version: Int, entries: List<Pair<Long, String>>, reserved: Boolean = version >= 1): ByteArray {
        var payload = if (reserved) u32(0) else ByteArray(0)
        payload += byteArrayOf(entries.size.toByte())
        for ((ms, title) in entries) {
            val t = title.toByteArray(Charsets.UTF_8)
            payload += u64(ms * 10_000) + byteArrayOf(t.size.toByte()) + t
        }
        return fullBox("chpl", version, payload)
    }

    fun moovWithChpl(chpl: ByteArray) = box("moov", fullBox("mvhd", 0, ByteArray(96)), box("udta", chpl))

    fun tkhd(id: Long) = fullBox("tkhd", 0, u32(0), u32(0), u32(id), ByteArray(68))

    fun mdhd(timescale: Long) = fullBox("mdhd", 0, u32(0), u32(0), u32(timescale), u32(0), u32(0))

    /** Piste texte de chapitres : [samples] sont posés à partir de [firstOffset] en [chunks] groupes. */
    fun textTrack(
        id: Long,
        timescale: Long,
        durations: List<Long>,
        sampleSizes: List<Int>,
        chunkOffsets: List<Long>,
        samplesPerChunk: List<Int>,
        wide: Boolean
    ): ByteArray {
        val stts =
            fullBox(
                "stts",
                0,
                u32(durations.size.toLong()),
                *durations.flatMap {
                    listOf(u32(1), u32(it))
                }.toTypedArray()
            )
        val stsz =
            fullBox(
                "stsz",
                0,
                u32(0),
                u32(sampleSizes.size.toLong()),
                *sampleSizes.map {
                    u32(it.toLong())
                }.toTypedArray()
            )
        val stsc = fullBox(
            "stsc",
            0,
            u32(samplesPerChunk.size.toLong()),
            *samplesPerChunk.mapIndexed { i, n -> u32(i + 1L) + u32(n.toLong()) + u32(1) }.toTypedArray()
        )
        val stco = if (wide) {
            fullBox("co64", 0, u32(chunkOffsets.size.toLong()), *chunkOffsets.map { u64(it) }.toTypedArray())
        } else {
            fullBox("stco", 0, u32(chunkOffsets.size.toLong()), *chunkOffsets.map { u32(it) }.toTypedArray())
        }
        return box(
            "trak",
            tkhd(id),
            box("mdia", mdhd(timescale), box("minf", box("stbl", stts, stsz, stsc, stco)))
        )
    }

    fun audioTrackReferencing(id: Long, chapterTrackId: Long) =
        box("trak", tkhd(id), box("tref", box("chap", u32(chapterTrackId))))

    fun textSample(text: String, utf16: Boolean = false): ByteArray {
        val body = if (utf16) {
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        } else {
            text.toByteArray(Charsets.UTF_8)
        }
        return u16(body.size) + body
    }
}
