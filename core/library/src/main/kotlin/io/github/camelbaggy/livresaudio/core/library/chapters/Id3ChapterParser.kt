package io.github.camelbaggy.livresaudio.core.library.chapters

import io.github.camelbaggy.livresaudio.core.model.ByteRangeSource
import io.github.camelbaggy.livresaudio.core.model.EmbeddedChapter

/**
 * Chapitres ID3v2.3/2.4 : trames `CHAP` (titre = sous-trame `TIT2`, repli `TIT3`) ordonnées par `CTOC`
 * si présente, sinon par début. Le tag est parcouru trame par trame en ne lisant que les en-têtes et
 * les trames `CHAP`/`CTOC` (les images `APIC` etc. sont sautées). Seul le cas « désynchronisation au
 * niveau du tag en v2.3 » impose de lire le tag en entier (les tailles de trames n'ont alors plus de sens
 * sur le fichier). Toute incohérence donne une liste vide.
 */
class Id3ChapterParser {
    suspend fun parse(source: ByteRangeSource): List<EmbeddedChapter> = try {
        doParse(source)
    } catch (_: MalformedDataException) {
        emptyList()
    } catch (_: IndexOutOfBoundsException) {
        emptyList()
    }

    private class Header(val major: Int, val flags: Int, val tagSize: Int)

    private class RawChapter(val id: String, val startMs: Long, val endMs: Long, val title: String?)

    private class Toc(val flags: Int, val children: List<String>)

    private class Collected {
        val chapters = ArrayList<RawChapter>()
        val tocs = LinkedHashMap<String, Toc>()
    }

    private suspend fun doParse(source: ByteRangeSource): List<EmbeddedChapter> {
        val headBytes = source.readFully(0, HEADER_SIZE)
        if (headBytes.size < HEADER_SIZE) return emptyList()
        val header = readHeader(headBytes) ?: return emptyList()
        var pos = HEADER_SIZE.toLong()
        val tagEnd = pos + header.tagSize
        val collected = Collected()
        val tagUnsync = header.flags and 0x80 != 0

        if (header.major == 3 && tagUnsync) {
            // Tag entier désynchronisé : lecture complète puis analyse en mémoire.
            if (header.tagSize > MAX_FULL_TAG_BYTES) return emptyList()
            val raw = source.readFully(pos, header.tagSize)
            if (raw.size < header.tagSize) return emptyList()
            val data = removeUnsynchronisation(raw)
            walkInMemory(data, header, collected)
        } else {
            val window = Window(source)
            if (header.flags and 0x40 != 0) pos += extendedHeaderSize(window, header, pos)
            walkFrames(window, header, pos, tagEnd, collected)
        }
        return assemble(collected)
    }

    private fun readHeader(b: ByteArray): Header? {
        if (b[0] != 'I'.code.toByte() || b[1] != 'D'.code.toByte() || b[2] != '3'.code.toByte()) return null
        val major = b[3].toInt() and 0xFF
        if (major != 3 && major != 4) return null
        val flags = b[5].toInt() and 0xFF
        val size = synchsafe(b, 6)
        if (size < 0) return null
        return Header(major, flags, size)
    }

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

    private suspend fun extendedHeaderSize(window: Window, header: Header, pos: Long): Long {
        val b = window.get(pos, 4) ?: malformed("en-tête étendu tronqué")
        return if (header.major == 4) {
            synchsafe(b, 0).also { if (it < 6) malformed("en-tête étendu invalide") }.toLong()
        } else {
            be32(b, 0) + 4
        }
    }

    // --- Parcours sur la source (v2.3 sans désynchronisation globale, v2.4) -----------------------

    private suspend fun walkFrames(window: Window, header: Header, start: Long, end: Long, out: Collected) {
        var pos = start
        var frames = 0
        while (pos + FRAME_HEADER_SIZE <= end) {
            if (++frames > MAX_FRAMES) malformed("trop de trames")
            val h = window.get(pos, FRAME_HEADER_SIZE) ?: return
            if (h[0].toInt() == 0) return // remplissage
            val frame = readFrameHeader(h, header.major)
            val bodyStart = pos + FRAME_HEADER_SIZE
            if (frame.size < 0 || bodyStart + frame.size > end) malformed("taille de trame incohérente")
            if ((frame.id == "CHAP" || frame.id == "CTOC") && frame.size <= MAX_FRAME_BYTES) {
                val body = window.get(bodyStart, frame.size.toInt()) ?: malformed("trame tronquée")
                handleFrame(frame, body, header, tagUnsync = header.flags and 0x80 != 0, out)
            }
            pos = bodyStart + frame.size
        }
    }

    // --- Parcours en mémoire (v2.3 désynchronisé) ---------------------------------------------------

    private fun walkInMemory(data: ByteArray, header: Header, out: Collected) {
        var pos = 0
        if (header.flags and 0x40 != 0) {
            if (data.size < 4) return
            pos = (be32(data, 0) + 4).toInt()
        }
        var frames = 0
        while (pos + FRAME_HEADER_SIZE <= data.size) {
            if (++frames > MAX_FRAMES) malformed("trop de trames")
            if (data[pos].toInt() == 0) return
            val frame = readFrameHeader(data.copyOfRange(pos, pos + FRAME_HEADER_SIZE), 3)
            val bodyStart = pos + FRAME_HEADER_SIZE
            if (frame.size < 0 || bodyStart + frame.size > data.size) malformed("taille de trame incohérente")
            if (frame.id == "CHAP" || frame.id == "CTOC") {
                handleFrame(frame, data.copyOfRange(bodyStart, bodyStart + frame.size.toInt()), header, false, out)
            }
            pos = bodyStart + frame.size.toInt()
        }
    }

    // --- Trames -----------------------------------------------------------------------------------

    private class FrameHeader(val id: String, val size: Long, val flags: Int)

    private fun readFrameHeader(h: ByteArray, major: Int): FrameHeader {
        val id = String(h, 0, 4, Charsets.ISO_8859_1)
        val size = if (major == 4) synchsafe(h, 4).toLong() else be32(h, 4)
        val flags = ((h[8].toInt() and 0xFF) shl 8) or (h[9].toInt() and 0xFF)
        return FrameHeader(id, size, flags)
    }

    /** Applique les drapeaux de trame (compression/chiffrement : ignorée ; désynchronisation retirée). */
    private fun frameBody(frame: FrameHeader, raw: ByteArray, header: Header, tagUnsync: Boolean): ByteArray? {
        var body = raw
        if (header.major == 4) {
            val f = frame.flags and 0xFF
            if (f and 0x0C != 0) return null // compression ou chiffrement
            var skip = 0
            if (f and 0x40 != 0) skip += 1 // identifiant de groupe
            if (f and 0x01 != 0) skip += 4 // indicateur de longueur de données
            if (skip > body.size) return null
            body = body.copyOfRange(skip, body.size)
            if (f and 0x02 != 0 || tagUnsync) body = removeUnsynchronisation(body)
        } else {
            val f = (frame.flags shr 8) and 0xFF
            if (f and 0xC0 != 0) return null // compression ou chiffrement
            if (f and 0x20 != 0) body = body.copyOfRange(minOf(1, body.size), body.size)
        }
        return body
    }

    private fun handleFrame(frame: FrameHeader, raw: ByteArray, header: Header, tagUnsync: Boolean, out: Collected) {
        val body = frameBody(frame, raw, header, tagUnsync) ?: return
        val r = ByteReader(body)
        val id = readLatin1Z(r)
        if (frame.id == "CHAP") {
            val start = r.u32()
            val end = r.u32()
            r.skip(8) // décalages en octets, inutilisés
            // Le corps est déjà resynchronisé : les sous-trames ne le sont pas une seconde fois.
            val title = readSubFrameTitle(r, header, tagUnsync = false)
            out.chapters += RawChapter(id, start, end, title)
        } else {
            val flags = r.u8()
            val count = r.u8()
            val children = ArrayList<String>(count)
            repeat(count) { children += readLatin1Z(r) }
            out.tocs.putIfAbsent(id, Toc(flags, children))
        }
    }

    private fun readLatin1Z(r: ByteReader): String {
        val sb = StringBuilder()
        while (true) {
            val c = r.u8()
            if (c == 0) return sb.toString()
            sb.append(c.toChar())
        }
    }

    private fun readSubFrameTitle(r: ByteReader, header: Header, tagUnsync: Boolean): String? {
        var tit2: String? = null
        var tit3: String? = null
        while (r.remaining >= FRAME_HEADER_SIZE) {
            val h = r.bytes(FRAME_HEADER_SIZE)
            if (h[0].toInt() == 0) break
            val sub = readFrameHeader(h, header.major)
            if (sub.size < 0 || sub.size > r.remaining) break
            val raw = r.bytes(sub.size.toInt())
            if (sub.id != "TIT2" && sub.id != "TIT3") continue
            val body = frameBody(sub, raw, header, tagUnsync) ?: continue
            val text = decodeTextFrame(body)
            if (sub.id == "TIT2" && tit2 == null) tit2 = text
            if (sub.id == "TIT3" && tit3 == null) tit3 = text
        }
        return tit2 ?: tit3
    }

    /** Trame de texte : octet d'encodage (0 Latin-1, 1 UTF-16+BOM, 2 UTF-16BE, 3 UTF-8), première valeur. */
    internal fun decodeTextFrame(body: ByteArray): String? {
        if (body.isEmpty()) return null
        val data = body.copyOfRange(1, body.size)
        return when (body[0].toInt()) {
            0 -> String(data.takeWhileNotZero(1), Charsets.ISO_8859_1)
            1 -> decodeUtf16WithBom(data)
            2 -> String(data.takeWhileNotZero(2), Charsets.UTF_16BE)
            3 -> String(data.takeWhileNotZero(1), Charsets.UTF_8)
            else -> null
        }
    }

    private fun decodeUtf16WithBom(data: ByteArray): String {
        if (data.size < 2) return ""
        val le = data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()
        val be = data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()
        val payload = if (le || be) data.copyOfRange(2, data.size) else data
        return String(payload.takeWhileNotZero(2), if (le) Charsets.UTF_16LE else Charsets.UTF_16BE)
    }

    /** Coupe au premier terminateur nul aligné sur [width] octets (1 ou 2). */
    private fun ByteArray.takeWhileNotZero(width: Int): ByteArray {
        var i = 0
        while (i + width <= size) {
            if ((0 until width).all { this[i + it].toInt() == 0 }) return copyOfRange(0, i)
            i += width
        }
        return this
    }

    // --- Assemblage ---------------------------------------------------------------------------------

    private fun assemble(c: Collected): List<EmbeddedChapter> {
        if (c.chapters.isEmpty()) return emptyList()
        val byId = LinkedHashMap<String, RawChapter>()
        for (ch in c.chapters) byId.putIfAbsent(ch.id, ch)
        val toc = c.tocs.values.firstOrNull { it.flags and 0x02 != 0 } ?: c.tocs.values.firstOrNull()
        val ordered = toc?.let { expand(it, c.tocs, byId, 0) }.orEmpty()
        val bySorted = byId.values.sortedBy { it.startMs }
        val monotone = ordered.zipWithNext().all { (a, b) -> a.startMs <= b.startMs }
        val list = if (ordered.isNotEmpty() && monotone) ordered else bySorted
        return normalizeChapters(list.map { EmbeddedChapter(it.title, it.startMs, it.endMs) })
    }

    private fun expand(
        toc: Toc,
        tocs: Map<String, Toc>,
        chapters: Map<String, RawChapter>,
        depth: Int
    ): List<RawChapter> {
        if (depth > MAX_TOC_DEPTH) return emptyList()
        return toc.children.flatMap { child ->
            chapters[child]?.let { listOf(it) }
                ?: tocs[child]?.let { expand(it, tocs, chapters, depth + 1) }.orEmpty()
        }
    }

    /** Retire l'insertion d'un 0x00 après chaque 0xFF (désynchronisation ID3). */
    internal fun removeUnsynchronisation(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    /** Tampon de lecture anticipée : évite une requête par en-tête de trame. */
    private class Window(private val source: ByteRangeSource) {
        private var start = 0L
        private var buf = ByteArray(0)

        suspend fun get(offset: Long, length: Int): ByteArray? {
            if (length < 0) return null
            val inWindow = offset >= start && offset + length <= start + buf.size
            if (!inWindow) {
                start = offset
                buf = source.readFully(offset, maxOf(length, READ_AHEAD))
            }
            val from = (offset - start).toInt()
            if (from + length > buf.size) return null
            return buf.copyOfRange(from, from + length)
        }
    }

    private companion object {
        const val HEADER_SIZE = 10
        const val FRAME_HEADER_SIZE = 10
        const val READ_AHEAD = 16 * 1024
        const val MAX_FRAMES = 20_000
        const val MAX_FRAME_BYTES = 1L shl 20
        const val MAX_FULL_TAG_BYTES = 16 shl 20
        const val MAX_TOC_DEPTH = 8
    }
}
