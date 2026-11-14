package io.github.camelbaggy.livresaudio.core.library

import java.text.Normalizer

/**
 * Natural ordering of names: digit runs compare by numeric value (`2` before `10`, `00` before `01`),
 * text compares case- and accent-insensitively. Ties fall back to the raw strings, so the order is total and stable.
 */
object NaturalSort : Comparator<String> {
    private val combiningMarks = Regex("\\p{M}+")

    /** Lower-case, accent-free form used for comparisons and matching keys. */
    fun fold(text: String): String =
        combiningMarks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()

    override fun compare(a: String, b: String): Int {
        val ca = chunks(fold(a))
        val cb = chunks(fold(b))
        var i = 0
        while (i < ca.size && i < cb.size) {
            val c = compareChunk(ca[i], cb[i])
            if (c != 0) return c
            i++
        }
        if (ca.size != cb.size) return ca.size - cb.size
        return a.compareTo(b)
    }

    /** Comparator on [DriveItem]-like objects through a name selector. */
    fun <T> by(selector: (T) -> String): Comparator<T> = Comparator { x, y -> compare(selector(x), selector(y)) }

    private fun chunks(s: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        for (i in 1..s.length) {
            if (i == s.length || s[i].isAsciiDigit() != s[i - 1].isAsciiDigit()) {
                out += s.substring(start, i)
                start = i
            }
        }
        return out
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private fun compareChunk(x: String, y: String): Int {
        val dx = x[0].isAsciiDigit()
        val dy = y[0].isAsciiDigit()
        return when {
            dx && dy -> {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            }

            dx -> -1

            dy -> 1

            else -> x.compareTo(y)
        }
    }
}
