package io.github.camelbaggy.livresaudio.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NaturalSortTest {
    private fun sorted(vararg names: String) = names.toList().sortedWith(NaturalSort)

    @Test
    fun numbersCompareByValue() {
        assertEquals(listOf("2", "10", "100"), sorted("100", "10", "2"))
        assertEquals(listOf("Chapitre 2", "Chapitre 10"), sorted("Chapitre 10", "Chapitre 2"))
    }

    @Test
    fun prologueBeforeFirstChapter() {
        assertEquals(
            listOf("00 - Prologue", "01 - Un", "02 - Deux", "81 - Remerciements"),
            sorted("81 - Remerciements", "02 - Deux", "00 - Prologue", "01 - Un")
        )
    }

    @Test
    fun chaptersSplitInTwoStayTogether() {
        assertEquals(
            listOf("002-Chapitre 2-1", "002-Chapitre 2-2", "010-Chapitre 10", "141 - Chapitre 141"),
            sorted("141 - Chapitre 141", "010-Chapitre 10", "002-Chapitre 2-2", "002-Chapitre 2-1")
        )
    }

    @Test
    fun caseAndAccentsAreIgnoredForOrder() {
        assertEquals(listOf("ecole", "Élan", "Zèbre"), sorted("Zèbre", "Élan", "ecole"))
        assertEquals(listOf("alpha", "Beta"), sorted("Beta", "alpha"))
    }

    @Test
    fun decimalVolumesFollowTheirParts() {
        assertEquals(
            listOf("S - 0 - a", "S - 1 - b", "S - 3 - c", "S - 3.5 - d", "S - 10 - e"),
            sorted("S - 10 - e", "S - 3.5 - d", "S - 3 - c", "S - 0 - a", "S - 1 - b")
        )
    }

    @Test
    fun orderIsTotalAndStable() {
        assertTrue(NaturalSort.compare("a01", "a1") != 0)
        assertEquals(0, NaturalSort.compare("same", "same"))
        assertEquals(sorted("a1", "a01"), sorted("a01", "a1"))
    }

    @Test
    fun hugeNumbersDoNotOverflow() {
        assertEquals(
            listOf("x99999999999999999999", "x100000000000000000000"),
            sorted("x100000000000000000000", "x99999999999999999999")
        )
    }
}
