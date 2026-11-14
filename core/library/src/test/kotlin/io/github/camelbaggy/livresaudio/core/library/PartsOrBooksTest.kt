package io.github.camelbaggy.livresaudio.core.library

import io.github.camelbaggy.livresaudio.core.library.PartsOrBooks.Decision.ONE_BOOK_PER_FILE
import io.github.camelbaggy.livresaudio.core.library.PartsOrBooks.Decision.PARTS_OF_ONE_BOOK
import kotlin.test.Test
import kotlin.test.assertEquals

class PartsOrBooksTest {
    private fun check(expected: PartsOrBooks.Decision, vararg names: String) =
        assertEquals(expected, PartsOrBooks.decide(names.toList()), names.joinToString())

    // region case 1 and 2: one book per file

    @Test
    fun seriesVolumesWithTitlesAreBooks() = check(
        ONE_BOOK_PER_FILE,
        "La Saga des Brumes - 1 - Le Premier Pas.m4b",
        "La Saga des Brumes - 0 - Avant le commencement.m4b",
        "La Saga des Brumes - 10 - Le Retour.m4b",
        "La Saga des Brumes - 3.5 - Une nouvelle.m4b"
    )

    @Test
    fun mixedFormatsInOneSeries() = check(
        ONE_BOOK_PER_FILE,
        "Cycle Sombre - 1 - Le Début.m4b",
        "Cycle Sombre - 2 - La Suite.mp3"
    )

    @Test
    fun authorFolderMixingIsolatedBooksAndSeries() = check(
        ONE_BOOK_PER_FILE,
        "Un roman isolé.m4b",
        "Autre Roman.mp3",
        "11 22 33.m4b",
        "Cycle Sombre - 2 - La Suite.m4b",
        "Cycle Sombre - 1 - Le Début.m4b"
    )

    @Test
    fun titleMadeOfNumbersIsABookNotATrack() = check(ONE_BOOK_PER_FILE, "11 22 33.m4b", "Un roman isolé.m4b")

    @Test
    fun seriesWithoutTitlesIsBooks() = check(ONE_BOOK_PER_FILE, "Série Exemple - 1.m4b", "Série Exemple - 2.m4b")

    @Test
    fun seriesWithoutTitlesWithDecimals() = check(
        ONE_BOOK_PER_FILE,
        "Série Exemple - 1.m4b",
        "Série Exemple - 1.5.m4b",
        "Série Exemple - 10.m4b"
    )

    @Test
    fun subSeriesVolumes() = check(
        ONE_BOOK_PER_FILE,
        "Préludes - 5.1 - Maison Une.m4b",
        "Préludes - 5.2 - Maison Deux.m4b"
    )

    @Test
    fun twoUnrelatedBooks() = check(ONE_BOOK_PER_FILE, "Roman A.mp3", "Roman B.mp3")

    // endregion

    // region case 4: chapters of one book

    @Test
    fun numberedChapters() = check(
        PARTS_OF_ONE_BOOK,
        "07 - Chapitre 7.mp3",
        "00 - Prologue.mp3",
        "81 - Remerciements.mp3",
        "08 - Chapitre 8.mp3"
    )

    @Test
    fun titleAndAuthorBetweenNumberAndChapter() = check(
        PARTS_OF_ONE_BOOK,
        "01 Le Second - Présentation.mp3",
        "15 Le Second - Auteure Exemple Chapitre 14.mp3",
        "16 Le Second - Auteure Exemple Chapitre 15.mp3"
    )

    @Test
    fun authorTitleChapterNumber() = check(
        PARTS_OF_ONE_BOOK,
        "07 Auteure Exemple Le Second Chapitre 07.mp3",
        "08 Auteure Exemple Le Second Chapitre 08.mp3",
        "09 Auteure Exemple Le Second Chapitre 09.mp3"
    )

    @Test
    fun noLeadingNumberJustFinalOne() = check(
        PARTS_OF_ONE_BOOK,
        "Auteure Exemple - Series Example 4- Le Quatrième Chapitre 05.mp3",
        "Auteure Exemple - Series Example 4- Le Quatrième Chapitre 06.mp3"
    )

    @Test
    fun volumeNumberCommonToAllChapters() = check(
        PARTS_OF_ONE_BOOK,
        "Le Sixième_ Series Example, tome 6 - 09 - Chapitre 9.mp3",
        "Le Sixième_ Series Example, tome 6 - 10 - Chapitre 10.mp3",
        "Le Sixième_ Series Example, tome 6 - 11 - Chapitre 11.mp3"
    )

    @Test
    fun chaptersSplitInTwo() = check(
        PARTS_OF_ONE_BOOK,
        "002-Chapitre 2-1.mp3",
        "002-Chapitre 2-2.mp3",
        "010-Chapitre 10.mp3",
        "141 - Chapitre 141.mp3"
    )

    @Test
    fun bareNumbers() = check(PARTS_OF_ONE_BOOK, "01.mp3", "02.mp3", "03.mp3")

    @Test
    fun trackNames() = check(PARTS_OF_ONE_BOOK, "Track 01.mp3", "Track 02.mp3", "Piste 3.mp3")

    @Test
    fun englishChapters() = check(PARTS_OF_ONE_BOOK, "Chapter 1.mp3", "Chapter 2.mp3", "Epilogue.mp3", "Prologue.mp3")

    @Test
    fun romanNumeralsAfterGenericWord() = check(PARTS_OF_ONE_BOOK, "Partie I.mp3", "Partie II.mp3", "Partie IV.mp3")

    @Test
    fun zeroPaddedNumbersAfterNameAreTracks() = check(PARTS_OF_ONE_BOOK, "Mon Livre 01.mp3", "Mon Livre 02.mp3")

    @Test
    fun discTrackNumbers() = check(PARTS_OF_ONE_BOOK, "01-01.mp3", "01-02.mp3", "02-01.mp3")

    // endregion

    // region edge cases

    @Test
    fun singleFileIsOneBook() = check(PARTS_OF_ONE_BOOK, "Seul.mp3")

    @Test
    fun noFileIsOneBook() = check(PARTS_OF_ONE_BOOK)

    @Test
    fun twoFilesIdenticalExceptNumberAreChapters() = check(PARTS_OF_ONE_BOOK, "Chapitre 1.mp3", "Chapitre 2.mp3")

    @Test
    fun onlyOneFileWithRealTextIsChapters() =
        check(PARTS_OF_ONE_BOOK, "01 - Prologue.mp3", "02 - Chapitre 1.mp3", "03 - Fin du voyage.mp3")

    @Test
    fun extensionsDoNotCount() = check(PARTS_OF_ONE_BOOK, "Chapitre 1.mp3", "Chapitre 2.m4a")

    @Test
    fun accentsAndCaseDoNotCount() = check(PARTS_OF_ONE_BOOK, "ÉPILOGUE.mp3", "prologue.mp3")

    @Test
    fun titleTextDetection() {
        assertEquals(true, PartsOrBooks.hasTitleText("Le Premier Pas.m4b"))
        assertEquals(false, PartsOrBooks.hasTitleText("07 - Chapitre 7.mp3"))
    }

    // endregion
}
