package io.github.camelbaggy.livresaudio.core.library.chapters

import io.github.camelbaggy.livresaudio.core.model.ByteRangeSource
import io.github.camelbaggy.livresaudio.core.model.EmbeddedChapter

/** Données incohérentes ou tronquées : le parseur concerné renvoie alors une liste vide. */
internal class MalformedDataException(message: String) : RuntimeException(message)

internal fun malformed(message: String): Nothing = throw MalformedDataException(message)

/** Lit exactement [length] octets si possible (plusieurs lectures si la source en renvoie moins). */
internal suspend fun ByteRangeSource.readFully(offset: Long, length: Int): ByteArray {
    if (length <= 0) return ByteArray(0)
    var out = ByteArray(length)
    var got = 0
    while (got < length) {
        val part = read(offset + got, length - got)
        if (part.isEmpty()) break
        part.copyInto(out, got, 0, minOf(part.size, length - got))
        got += minOf(part.size, length - got)
    }
    if (got < length) out = out.copyOf(got)
    return out
}

/** Lecteur borné sur un tableau d'octets ; toute lecture hors limites lève [MalformedDataException]. */
internal class ByteReader(val data: ByteArray, var pos: Int = 0, val end: Int = data.size) {
    init {
        if (pos < 0 || end > data.size || pos > end) malformed("bornes invalides")
    }

    val remaining: Int get() = end - pos

    private fun need(n: Int) {
        if (n < 0 || n > end - pos) malformed("lecture au-delà de la fin")
    }

    fun u8(): Int {
        need(1)
        return data[pos++].toInt() and 0xFF
    }

    fun u16(): Int = (u8() shl 8) or u8()

    fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

    fun u64(): Long {
        val hi = u32()
        val lo = u32()
        if (hi and 0x80000000L != 0L) malformed("entier 64 bits trop grand")
        return (hi shl 32) or lo
    }

    fun skip(n: Long) {
        if (n < 0 || n > remaining) malformed("saut au-delà de la fin")
        pos += n.toInt()
    }

    fun bytes(n: Int): ByteArray {
        need(n)
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun fourCc(): String {
        need(4)
        val s = String(data, pos, 4, Charsets.ISO_8859_1)
        pos += 4
        return s
    }
}

/**
 * Normalisation commune (pure) : titres vides → null, début négatif écarté, doublons de début
 * supprimés (on garde celui qui a un titre), fin manquante ou non croissante = début du suivant.
 * Une fin explicite `<= début` sur le dernier chapitre est considérée comme inconnue (null).
 * Ne trie pas : l'ordre d'entrée est celui du fichier.
 */
internal fun normalizeChapters(raw: List<EmbeddedChapter>): List<EmbeddedChapter> {
    val cleaned = raw
        .filter { it.startMs >= 0 }
        .map { it.copy(title = it.title?.trim()?.takeIf(String::isNotEmpty)) }
    val unique = ArrayList<EmbeddedChapter>(cleaned.size)
    for (c in cleaned) {
        val prev = unique.lastOrNull()
        when {
            prev == null || prev.startMs != c.startMs -> unique += c
            prev.title == null && c.title != null -> unique[unique.size - 1] = c
        }
    }
    return unique.mapIndexed { i, c ->
        val next = unique.getOrNull(i + 1)
        val end = c.endMs?.takeIf { it > c.startMs } ?: next?.startMs
        c.copy(endMs = end)
    }
}

/** Décode un texte avec détection de BOM UTF-16 (sinon UTF-8). */
internal fun decodeTextWithBom(bytes: ByteArray): String = when {
    bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
        String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)

    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
        String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)

    bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
        String(bytes, 3, bytes.size - 3, Charsets.UTF_8)

    else -> String(bytes, Charsets.UTF_8)
}
