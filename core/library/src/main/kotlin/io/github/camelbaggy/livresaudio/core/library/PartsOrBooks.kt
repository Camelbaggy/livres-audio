package io.github.camelbaggy.livresaudio.core.library

/**
 * Decision "one book or several?" for a folder directly holding at least two audio files
 * (rule documented in `docs/formes-de-noms.md`).
 *
 * 1. Strip the extension and drop numbers and generic words (`chapitre`, `partie`, `prologue`...). Common leading
 *    and trailing words do not change the count of distinct leftovers, they only serve the refinement below.
 * 2. Nothing left anywhere, or only one distinct text left (a text that starts another one counts as the same,
 *    as in `01 Titre - Présentation` / `15 Titre - Auteur Chapitre 14`): chapters of one book.
 * 3. At least two distinct leftovers: one book per file.
 *
 * Two refinements keep real cases right:
 * - `Série - 1`, `Série - 2` (no title, plain non-padded numbers after a non-generic common prefix) are books;
 *   `Livre 01`, `Livre 02` (zero-padded) or `Chapitre 1` (generic prefix) are chapters.
 * - A name made of several numbers (`11 22 33`) is a title, not a track number, when the other names contain text.
 *
 * Known limitation: chapters named with real titles and no generic word (`01 - Le début`, `02 - La suite`)
 * are taken for separate books.
 */
object PartsOrBooks {
    enum class Decision { PARTS_OF_ONE_BOOK, ONE_BOOK_PER_FILE }

    private val genericWords = setOf(
        "chapitre", "chapter", "chap", "partie", "part", "piste", "track", "disque", "disc", "cd",
        "prologue", "epilogue", "introduction", "presentation", "remerciements", "credits", "annexe", "interlude"
    )
    private val tokenRegex = Regex("""\d+(?:[.,]\d+)?|\p{L}+(?:['’]\p{L}+)*""")
    private val numberRegex = Regex("""\d+(?:[.,]\d+)?""")
    private val romanRegex = Regex("^[ivxlcdm]+$")

    fun decide(fileNames: List<String>): Decision {
        if (fileNames.size < 2) return Decision.PARTS_OF_ONE_BOOK
        val tokens = fileNames.map { tokenize(it) }

        val prefix = commonPrefixLength(tokens)
        val suffix = commonSuffixLength(tokens, prefix)
        val rests = tokens.map { it.subList(prefix, it.size - suffix) }

        val anyTextual = tokens.any { list -> list.any { !isNumber(it) } }
        val leftovers = tokens.indices.map { i ->
            val whole = tokens[i]
            if (anyTextual && whole.size >= 2 && whole.all { isNumber(it) }) {
                whole.joinToString(" ") // a title made of numbers
            } else {
                meaningful(whole, prefix, suffix).joinToString(" ")
            }
        }
        val distinct = distinctTexts(leftovers)
        return when {
            distinct.size >= 2 -> Decision.ONE_BOOK_PER_FILE
            distinct.isEmpty() && isNumberedSeries(tokens, prefix, rests) -> Decision.ONE_BOOK_PER_FILE
            else -> Decision.PARTS_OF_ONE_BOOK
        }
    }

    /** Distinct non-empty texts, where a text that is a word-prefix of another one counts as the same. */
    private fun distinctTexts(texts: List<String>): List<String> {
        val unique = texts.filter { it.isNotEmpty() }.toSet().sortedByDescending { it.length }
        val kept = ArrayList<String>()
        for (t in unique) if (kept.none { it == t || it.startsWith("$t ") }) kept += t
        return kept
    }

    /** True when [fileName] carries some real text, not only numbers and generic words. */
    fun hasTitleText(fileName: String): Boolean = meaningful(tokenize(fileName)).isNotEmpty()

    private fun tokenize(fileName: String): List<String> =
        tokenRegex.findAll(NaturalSort.fold(AudioFormats.stripKnownExtension(fileName))).map { it.value }.toList()

    private fun isNumber(token: String) = numberRegex.matches(token)

    private fun commonPrefixLength(lists: List<List<String>>): Int {
        val limit = lists.minOf { it.size }
        var n = 0
        while (n < limit && lists.all { it[n] == lists[0][n] }) n++
        return n
    }

    private fun commonSuffixLength(lists: List<List<String>>, prefix: Int): Int {
        val limit = lists.minOf { it.size } - prefix
        var n = 0
        while (n < limit && lists.all { it[it.size - 1 - n] == lists[0][lists[0].size - 1 - n] }) n++
        return n
    }

    /**
     * Words that carry meaning: no numbers, no generic words, no roman numeral directly after a generic word,
     * and nothing inside the common [prefix] or [suffix] when given.
     */
    private fun meaningful(tokens: List<String>, prefix: Int = 0, suffix: Int = 0): List<String> {
        val out = ArrayList<String>()
        var previousGeneric = false
        for ((i, t) in tokens.withIndex()) {
            val generic = t in genericWords
            val drop = generic || isNumber(t) || (previousGeneric && romanRegex.matches(t))
            val common = i < prefix || i >= tokens.size - suffix
            if (!drop && !common) out += t
            previousGeneric = generic
        }
        return out
    }

    private fun isNumberedSeries(tokens: List<List<String>>, prefix: Int, rests: List<List<String>>): Boolean {
        if (prefix == 0) return false
        val head = tokens[0].subList(0, prefix)
        if (head.none { !isNumber(it) && it !in genericWords }) return false
        if (rests.any { it.isEmpty() || !it.all { t -> isNumber(t) } }) return false
        if (rests.any { r -> r.any { it.length >= 2 && it.startsWith("0") && it[1] != '.' && it[1] != ',' } }) {
            return false // zero-padded: track numbers
        }
        return rests.map { it.joinToString(" ") }.toSet().size == rests.size
    }
}
