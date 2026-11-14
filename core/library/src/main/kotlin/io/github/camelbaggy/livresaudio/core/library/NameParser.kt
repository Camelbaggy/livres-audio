package io.github.camelbaggy.livresaudio.core.library

import io.github.camelbaggy.livresaudio.core.model.ParsedName

/** What the outermost grouping folder under a watched root means (CLAUDE.md §5.2, docs/formes-de-noms.md). */
enum class GroupingRole {
    /** Guess: the outermost grouping is an author, unless the book name shows it is the series. */
    AUTO,

    /** Personal Drive: `Auteur/[Série]/livres`. */
    AUTHOR,

    /** Shared Drive: `Série/livres`. */
    SERIES
}

/**
 * Pure analysis of folder and file names (result provenance: FOLDER).
 *
 * Strategy:
 * 1. Strip a known audio extension, remove noise (bitrates, `mp3`, `audiobook`, `livre audio`, `intégrale`, `VF`,
 *    bracketed years) and extract the narrator (`lu par X`, `narrated by X`).
 * 2. Recognise, in this order: `Titre_ Série, tome N`; a leading `[Série T3]`; a trailing `(Auteur)`;
 *    a leading order prefix `03. `; then split on dashes (with a space on at least one side, so `Hors-série` is kept):
 *    - 2 parts: `Série - N`, `Série - Tome 3`, `Série N - Titre`, `Auteur - Série N`, `Auteur - Titre`
 *    - 3 parts: `Série - N - Titre`, `Série - Tome 3 - Titre`, `Auteur - Série N - Titre`, else `Auteur - Titre - Sous-titre`
 *    - 4 parts: `Auteur - Série - N - Titre`
 *    A leading pure-number part (`1 - Planète Sable`, `03 - Titre`) is an order prefix and is dropped.
 *    A series number is `N`, `N.M`, `N,M` or `N M` (single digit M: `3 5` is 3.5), 1 or 2 digits before the decimal
 *    part (so a title ending with `451` is not taken for a volume). A volume without title gets the title
 *    "Série N" so that every book has a displayable title.
 * 3. Case: names written all in capitals or all in lower case get title case; mixed case is left alone.
 * 4. Hints from [groupingNames] (outermost first) fill only what the name does not say; the name always wins:
 *    - a grouping prefixed `N - ` is a numbered sub-series: its name is the series hint and `N` is NOT a volume;
 *    - the outermost unnumbered grouping is the author ([GroupingRole.AUTHOR], default [GroupingRole.AUTO]) unless
 *      it is the same series as the one read in the name; with [GroupingRole.SERIES] it is the series;
 *    - otherwise the innermost unnumbered grouping below the outermost one is the series hint.
 *
 * Ambiguity accepted: `Auteur - Titre 12` is read as series "Titre", volume 12.
 */
object NameParser {
    private data class Raw(
        val title: String? = null,
        val author: String? = null,
        val series: String? = null,
        val index: Double? = null
    )

    private data class Ref(val series: String, val index: Double, val viaMarker: Boolean)

    private const val NUM = """\d+(?:[.,]\d+)?"""
    private const val NOISE =
        """mp3|m4b|m4a|vf|audiobooks?|livres?\s+audio|int[eé]grale|unabridged|abridged|""" +
            """\d{2,3}\s*k(?:bps|b/s|bit/s)?|(?:19|20)\d{2}"""
    private const val MARKER = """(?:tome|t|vol\.?|volume|book|livre|n°|no\.?|#)"""

    private val bracketRegex = Regex("""[(\[]([^()\[\]]*)[)\]]""")
    private val noiseOnlyRegex = Regex("""(?iu)^(?:$NOISE)(?:[\s,;.\-–]+(?:$NOISE))*$""")
    private val narratorInnerRegex =
        Regex("""(?iu)^(?:lu\s+par|narrated\s+by|read\s+by|narrateur\s*:?|narrator\s*:?)\s+(.+)$""")
    private val narratorBareRegex =
        Regex("""(?iu)(?:^|\s+)(?:lu\s+par|narrated\s+by|read\s+by)\s+(.+?)(?=\s+[-–—]\s+|\s*[(\[]|\s*$)""")
    private val bareNoiseRegex = Regex(
        """(?iu)(?<![\p{L}\p{N}'’])""" +
            """(?:mp3|\d{2,3}\s*kb(?:ps|/s)|audiobooks?|livres?\s+audio|int[eé]grale|vf|unabridged)""" +
            """(?![\p{L}\p{N}'’])"""
    )
    private val repeatedDashRegex = Regex("""(?:\s[-–—]+){2,}(?=\s|$)""")
    private val edgeSeparatorRegex = Regex("""^[\s\-–—,;:]+|[\s\-–—,;:]+$""")
    private val spacesRegex = Regex("""\s+""")

    private val colonFormRegex = Regex("""(?iu)^(.+?)_\s+(.+?),?\s+tome\s+($NUM)$""")
    private val leadingBracketRegex = Regex("""^\[([^\]]+)]\s*(.+)$""")
    private val trailingParenRegex = Regex("""^(.+?)\s*\(([^()]+)\)$""")
    private val orderDotRegex = Regex("""^\d{1,3}\.\s+(?=.*\p{L})""")
    private val dashSplitRegex = Regex("""\s+[-–—]+\s*|\s*[-–—]+\s+""")
    private val orderPrefixRegex = Regex("""^\d+(?:[.,]\d+)?\s*(?:[-–—]|\.(?=\s))\s*(.+)$""")

    private val pureNumberRegex = Regex("^$NUM$")
    private val orderNumberRegex = Regex("""^\d{1,3}$""")
    private val markerOnlyRegex = Regex("""(?iu)^(?:$MARKER|épisode|episode)\s*($NUM)$""")
    private val refMarkerRegex = Regex("""(?iu)^(.+?)[,\s]+$MARKER\s*($NUM)$""")
    private val refPairRegex = Regex("""^(.*\D)\s+(\d{1,2})\s(\d)$""")
    private val refTrailRegex = Regex("""^(.*\p{L}.*?)\s+(\d{1,2}(?:[.,]\d+)?)$""")

    private val smallWords = setOf(
        "de", "du", "des", "la", "le", "les", "et", "en", "un", "une", "au", "aux", "of", "the", "a", "an", "and",
        "in", "on", "to", "for"
    )

    fun parse(
        name: String,
        groupingNames: List<String> = emptyList(),
        role: GroupingRole = GroupingRole.AUTO
    ): ParsedName {
        val (cleaned, narrator) = removeNoise(AudioFormats.stripKnownExtension(name))
        val hintKeys = groupingNames.map { key(stripOrderPrefix(it).first) }.toSet()
        val raw = if (cleaned.isEmpty()) Raw() else interpret(cleaned, hintKeys)
        return applyHints(raw, groupingNames, role).let {
            ParsedName(
                title = tidy(it.title),
                author = tidy(it.author),
                narrator = tidy(narrator, fixCase = false),
                seriesName = tidy(it.series),
                seriesIndex = it.index
            )
        }
    }

    /** Matching key for series and author names: lower case, no accents, no punctuation, single spaces. */
    fun key(text: String): String =
        spacesRegex.replace(NaturalSort.fold(text).replace(Regex("""[^\p{L}\p{N}]+"""), " "), " ").trim()

    /**
     * Splits a numbered grouping name (`5 - Préludes`) into its name and its order number.
     * The second member is null when [name] has no `N - ` prefix.
     */
    fun stripOrderPrefix(name: String): Pair<String, Double?> {
        val trimmed = name.trim()
        val m = orderPrefixRegex.matchEntire(trimmed) ?: return trimmed to null
        val number = Regex("^$NUM").find(trimmed)!!.value
        return m.groupValues[1].trim() to parseNumber(number)
    }

    // region noise

    private fun removeNoise(text: String): Pair<String, String?> {
        var narrator: String? = null
        var s = bracketRegex.replace(text.replace(' ', ' ')) { m ->
            val inner = m.groupValues[1].trim()
            val n = narratorInnerRegex.matchEntire(inner)
            when {
                n != null -> {
                    narrator = n.groupValues[1].trim()
                    " "
                }

                noiseOnlyRegex.matches(inner) -> " "

                else -> m.value
            }
        }
        s = narratorBareRegex.replace(s) { m ->
            if (narrator == null) narrator = m.groupValues[1].trim()
            " "
        }
        s = bareNoiseRegex.replace(s, " ")
        s = spacesRegex.replace(s, " ")
        s = repeatedDashRegex.replace(s, " -")
        s = edgeSeparatorRegex.replace(s.trim(), "")
        return spacesRegex.replace(s, " ").trim() to narrator
    }

    // endregion

    // region structure

    private fun interpret(cleaned: String, hintKeys: Set<String>): Raw {
        colonFormRegex.matchEntire(cleaned)?.let {
            val series = it.groupValues[2].trim()
            return Raw(title = it.groupValues[1].trim(), series = series, index = parseNumber(it.groupValues[3]))
        }
        var t = spacesRegex.replace(cleaned.replace('_', ' '), " ").trim()
        var series: String? = null
        var index: Double? = null
        var author: String? = null

        leadingBracketRegex.matchEntire(t)?.let { m ->
            val content = m.groupValues[1].trim()
            val parts = splitDashes(content)
            val ref = parseSeriesRef(content)
            when {
                parts.size == 2 && pureNumberRegex.matches(parts[1]) -> {
                    series = parts[0]
                    index = parseNumber(parts[1])
                }

                ref != null -> {
                    series = ref.series
                    index = ref.index
                }

                else -> series = content
            }
            t = m.groupValues[2].trim()
        }
        trailingParenRegex.matchEntire(t)?.let { m ->
            val content = m.groupValues[2].trim()
            val ref = parseSeriesRef(content)
            val marker = markerOnlyRegex.matchEntire(content)
            when {
                ref != null -> {
                    series = series ?: ref.series
                    index = index ?: ref.index
                    t = m.groupValues[1].trim()
                }

                marker != null -> {
                    index = index ?: parseNumber(marker.groupValues[1])
                    t = m.groupValues[1].trim()
                }

                content.none { it.isDigit() } -> {
                    author = content
                    t = m.groupValues[1].trim()
                }
            }
        }
        t = orderDotRegex.replaceFirst(t, "")

        val inner = interpretSegments(splitDashes(t), hintKeys)
        return Raw(
            title = inner.title,
            author = author ?: inner.author,
            series = series ?: inner.series,
            index = index ?: inner.index
        )
    }

    private fun splitDashes(s: String): List<String> =
        dashSplitRegex.split(s).map { it.trim() }.filter { it.isNotEmpty() }

    private fun interpretSegments(rawSegs: List<String>, hintKeys: Set<String>): Raw {
        var segs = rawSegs
        while (segs.size >= 2 && orderNumberRegex.matches(segs[0])) segs = segs.drop(1)
        return when (segs.size) {
            0 -> Raw()

            1 -> single(segs[0], hintKeys)

            2 -> two(segs[0], segs[1])

            3 -> three(segs[0], segs[1], segs[2])

            else -> {
                if (pureNumberRegex.matches(segs[2])) {
                    Raw(
                        title = segs.drop(3).joinToString(" - "),
                        author = segs[0],
                        series = segs[1],
                        index = parseNumber(segs[2])
                    )
                } else {
                    Raw(title = segs.drop(1).joinToString(" - "), author = segs[0])
                }
            }
        }
    }

    private fun single(seg: String, hintKeys: Set<String>): Raw {
        val ref = parseSeriesRef(seg)
        if (ref != null && (ref.viaMarker || key(ref.series) in hintKeys)) return volume(ref.series, ref.index)
        return Raw(title = seg)
    }

    private fun two(a: String, b: String): Raw {
        if (pureNumberRegex.matches(b)) return volume(a, parseNumber(b))
        markerOnlyRegex.matchEntire(b)?.let { return volume(a, parseNumber(it.groupValues[1])) }
        parseSeriesRef(a)?.let { return Raw(title = b, series = it.series, index = it.index) }
        parseSeriesRef(b)?.let { return volume(it.series, it.index, author = a) }
        return Raw(title = b, author = a)
    }

    private fun three(a: String, b: String, c: String): Raw {
        if (pureNumberRegex.matches(b)) return Raw(title = c, series = a, index = parseNumber(b))
        markerOnlyRegex.matchEntire(b)?.let {
            return Raw(title = c, series = a, index = parseNumber(it.groupValues[1]))
        }
        parseSeriesRef(b)?.let { return Raw(title = c, author = a, series = it.series, index = it.index) }
        return Raw(title = "$b - $c", author = a)
    }

    private fun volume(series: String, index: Double, author: String? = null) =
        Raw(title = "$series ${formatNumber(index)}", author = author, series = series, index = index)

    private fun parseSeriesRef(text: String): Ref? {
        refMarkerRegex.matchEntire(text)?.let {
            return Ref(it.groupValues[1].trim(), parseNumber(it.groupValues[2]), viaMarker = true)
        }
        refPairRegex.matchEntire(text)?.let {
            val n = parseNumber("${it.groupValues[2]}.${it.groupValues[3]}")
            return Ref(it.groupValues[1].trim(), n, viaMarker = false)
        }
        refTrailRegex.matchEntire(text)?.let {
            return Ref(it.groupValues[1].trim(), parseNumber(it.groupValues[2]), viaMarker = false)
        }
        return null
    }

    // endregion

    // region hints

    private fun applyHints(raw: Raw, groupingNames: List<String>, role: GroupingRole): Raw {
        if (groupingNames.isEmpty()) return raw
        val stripped = groupingNames.map { stripOrderPrefix(it) }
        val outermost = groupingNames.first().takeIf { stripped.first().second == null }
        val innerNumbered = stripped.lastOrNull { it.second != null }?.first
        val innerPlain = stripped.drop(1).lastOrNull { it.second == null }?.first

        var series = raw.series
        if (series == null) {
            series = innerNumbered ?: when (role) {
                GroupingRole.SERIES -> outermost
                else -> innerPlain
            }
        }
        var author = raw.author
        if (author == null && role != GroupingRole.SERIES && outermost != null) {
            val sameAsSeries = series != null && key(series) == key(outermost)
            if (!sameAsSeries) author = outermost
        }
        return raw.copy(author = author, series = series)
    }

    // endregion

    // region text helpers

    private fun parseNumber(text: String): Double = text.replace(',', '.').toDouble()

    private fun formatNumber(n: Double): String =
        if (n == Math.floor(n) && !n.isInfinite()) n.toLong().toString() else n.toString()

    private fun tidy(text: String?, fixCase: Boolean = true): String? {
        val s = text?.let { spacesRegex.replace(it.replace('_', ' '), " ").trim() }?.takeIf { it.isNotEmpty() }
        return if (fixCase && s != null) normalizeCase(s) else s
    }

    /** All-caps or all-lower-case text gets title case; mixed case is kept as written. */
    internal fun normalizeCase(s: String): String {
        val letters = s.filter { it.isLetter() }
        if (letters.length < 4 || !(letters.all { it.isUpperCase() } || letters.all { it.isLowerCase() })) return s
        return s.split(' ').mapIndexed { i, w ->
            val lower = w.lowercase()
            if (i > 0 && lower in smallWords) lower else capitalize(lower)
        }.joinToString(" ")
    }

    private fun capitalize(w: String): String {
        val first = w.replaceFirstChar { it.uppercase() }
        val apostrophe = first.indexOfFirst { it == '\'' || it == '’' }
        return if (apostrophe == 1 && first[0] == 'L' && first.length > 2) {
            first.substring(0, 2) + first[2].uppercase() + first.substring(3)
        } else {
            first
        }
    }

    // endregion
}
