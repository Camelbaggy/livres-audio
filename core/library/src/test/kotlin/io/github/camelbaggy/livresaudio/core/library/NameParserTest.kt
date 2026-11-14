package io.github.camelbaggy.livresaudio.core.library

import io.github.camelbaggy.livresaudio.core.model.ParsedName
import kotlin.test.Test
import kotlin.test.assertEquals

/** All names are invented, with the shapes observed in docs/formes-de-noms.md. */
class NameParserTest {
    private class Case(
        val name: String,
        val expected: ParsedName,
        val grouping: List<String> = emptyList(),
        val role: GroupingRole = GroupingRole.AUTO
    )

    private fun p(
        title: String? = null,
        author: String? = null,
        series: String? = null,
        index: Double? = null,
        narrator: String? = null
    ) = ParsedName(title = title, author = author, narrator = narrator, seriesName = series, seriesIndex = index)

    private fun run(cases: List<Case>) = cases.forEach {
        assertEquals(it.expected, NameParser.parse(it.name, it.grouping, it.role), "« ${it.name} » ${it.grouping}")
    }

    @Test
    fun specForms() = run(
        listOf(
            Case("Alice Durand - Le Jardin Secret", p("Le Jardin Secret", "Alice Durand")),
            Case("Alice Durand - Cycle Sombre 03 - Le Jardin", p("Le Jardin", "Alice Durand", "Cycle Sombre", 3.0)),
            Case("Cycle Sombre - Tome 3 - Le Jardin", p("Le Jardin", null, "Cycle Sombre", 3.0)),
            Case("Le Jardin Secret (Alice Durand)", p("Le Jardin Secret", "Alice Durand")),
            Case("[Cycle Sombre T3] Le Jardin", p("Le Jardin", null, "Cycle Sombre", 3.0)),
            Case("03. Le Jardin Secret", p("Le Jardin Secret")),
            Case("Cycle Sombre 03 - Le Jardin", p("Le Jardin", null, "Cycle Sombre", 3.0)),
            Case("[Cycle Sombre 3] Le Jardin", p("Le Jardin", null, "Cycle Sombre", 3.0)),
            Case("[Cycle Sombre - 3] Le Jardin", p("Le Jardin", null, "Cycle Sombre", 3.0)),
            Case("Le Jardin (Cycle Sombre, tome 3)", p("Le Jardin", null, "Cycle Sombre", 3.0))
        )
    )

    @Test
    fun seriesVolumeTitleForms() = run(
        listOf(
            Case("La Saga des Brumes - 1 - Le Premier Pas.m4b", p("Le Premier Pas", null, "La Saga des Brumes", 1.0)),
            Case(
                "La Saga des Brumes - 0 - Avant le commencement.m4b",
                p("Avant le commencement", null, "La Saga des Brumes", 0.0)
            ),
            Case("La Saga des Brumes - 10 - Le Retour.m4b", p("Le Retour", null, "La Saga des Brumes", 10.0)),
            Case("La Saga des Brumes - 3.5 - Une nouvelle.m4b", p("Une nouvelle", null, "La Saga des Brumes", 3.5)),
            Case("Préludes - 5.1 - Maison Une.m4b", p("Maison Une", null, "Préludes", 5.1)),
            Case("Préludes - 5,2 - Maison Deux.m4b", p("Maison Deux", null, "Préludes", 5.2)),
            Case("Planète Sable - 1 - Planète Sable.m4b", p("Planète Sable", null, "Planète Sable", 1.0)),
            Case("Cycle Sombre - 2 - La Suite.mp3", p("La Suite", null, "Cycle Sombre", 2.0))
        )
    )

    @Test
    fun seriesVolumeWithoutTitle() = run(
        listOf(
            Case("Série Exemple - 1.m4b", p("Série Exemple 1", null, "Série Exemple", 1.0)),
            Case("Série Exemple - 2.mp3", p("Série Exemple 2", null, "Série Exemple", 2.0)),
            Case("Série Exemple - 1.5.m4b", p("Série Exemple 1.5", null, "Série Exemple", 1.5)),
            Case("Série Exemple - 1.5", p("Série Exemple 1.5", null, "Série Exemple", 1.5)),
            Case("Série Exemple - Tome 4", p("Série Exemple 4", null, "Série Exemple", 4.0)),
            Case("Série Exemple, tome 4", p("Série Exemple 4", null, "Série Exemple", 4.0))
        )
    )

    @Test
    fun authorSeriesVolumeWithoutDashBetweenSeriesAndNumber() = run(
        listOf(
            Case("Auteure Exemple - Série Exemple 1", p("Série Exemple 1", "Auteure Exemple", "Série Exemple", 1.0)),
            Case(
                "Auteure Exemple - Série Exemple 2 - Le Second",
                p("Le Second", "Auteure Exemple", "Série Exemple", 2.0)
            ),
            Case(
                "Auteure Exemple - Série Exemple 0 - Les Origines",
                p("Les Origines", "Auteure Exemple", "Série Exemple", 0.0)
            ),
            Case(
                "Auteure Exemple - Série Exemple 3 5 - Le Hors-série",
                p("Le Hors-série", "Auteure Exemple", "Série Exemple", 3.5)
            ),
            Case(
                "Auteure Exemple - Série Exemple 3.5 - Le Hors-série",
                p("Le Hors-série", "Auteure Exemple", "Série Exemple", 3.5)
            ),
            Case("Série Exemple 3 5 - Le Hors-série", p("Le Hors-série", null, "Série Exemple", 3.5)),
            Case(
                "Auteure Exemple - Série Exemple 12 - Le Douzième",
                p("Le Douzième", "Auteure Exemple", "Série Exemple", 12.0)
            ),
            Case("Auteure Exemple - verre 4 - Le Verre", p("Le Verre", "Auteure Exemple", "Verre", 4.0))
        )
    )

    @Test
    fun colonReplacedByUnderscoreForm() = run(
        listOf(
            Case("Le Sixième_ Series Example, tome 6", p("Le Sixième", null, "Series Example", 6.0)),
            Case("Le Sixième_ Series Example, tome 6.mp3", p("Le Sixième", null, "Series Example", 6.0))
        )
    )

    @Test
    fun noiseIsRemoved() = run(
        listOf(
            Case("Le Jardin Secret [mp3]", p("Le Jardin Secret")),
            Case("Le Jardin Secret (64kbps)", p("Le Jardin Secret")),
            Case("Le Jardin Secret 128 kbps", p("Le Jardin Secret")),
            Case("Le Jardin Secret - audiobook", p("Le Jardin Secret")),
            Case("Le Jardin Secret - Livre audio", p("Le Jardin Secret")),
            Case("Le Jardin Secret (intégrale)", p("Le Jardin Secret")),
            Case("Le Jardin Secret Intégrale", p("Le Jardin Secret")),
            Case("Le Jardin Secret VF", p("Le Jardin Secret")),
            Case("Le Jardin Secret (2015)", p("Le Jardin Secret")),
            Case("Le Jardin Secret [2015]", p("Le Jardin Secret")),
            Case("Le Jardin Secret (VF) [mp3] (2015)", p("Le Jardin Secret")),
            Case("Alice Durand - Le Jardin Secret [mp3 - 64kbps]", p("Le Jardin Secret", "Alice Durand")),
            Case("L'Intégrale du jardin", p("L'Intégrale du jardin")),
            Case("1984", p("1984")),
            Case("Mp3", p())
        )
    )

    @Test
    fun narratorIsExtracted() = run(
        listOf(
            Case("Le Jardin Secret (lu par Jean Martin)", p("Le Jardin Secret", narrator = "Jean Martin")),
            Case("Le Jardin Secret - lu par Jean Martin", p("Le Jardin Secret", narrator = "Jean Martin")),
            Case("Le Jardin Secret lu par Jean Martin", p("Le Jardin Secret", narrator = "Jean Martin")),
            Case(
                "Alice Durand - Le Jardin Secret [Lu par Jean-Pierre Martin]",
                p("Le Jardin Secret", "Alice Durand", narrator = "Jean-Pierre Martin")
            ),
            Case("The Hidden Garden (narrated by Jane Smith)", p("The Hidden Garden", narrator = "Jane Smith")),
            Case(
                "The Hidden Garden - narrated by Jane Smith - 64kbps",
                p("The Hidden Garden", narrator = "Jane Smith")
            ),
            Case(
                "Alice Durand - Cycle Sombre - 2 - La Suite (lu par Jean Martin)",
                p("La Suite", "Alice Durand", "Cycle Sombre", 2.0, "Jean Martin")
            )
        )
    )

    @Test
    fun spacesUnderscoresAndCase() = run(
        listOf(
            Case("Le_Jardin_Secret", p("Le Jardin Secret")),
            Case("Alice_Durand_-_Le_Jardin_Secret", p("Le Jardin Secret", "Alice Durand")),
            Case("  Le   Jardin    Secret  ", p("Le Jardin Secret")),
            Case("LE JARDIN SECRET", p("Le Jardin Secret")),
            Case("le jardin secret", p("Le Jardin Secret")),
            Case("ALICE DURAND - LE JARDIN DES MERS", p("Le Jardin des Mers", "Alice Durand")),
            Case("Le Jardin SECRET", p("Le Jardin SECRET")),
            Case("L'ÎLE AUX TRÉSORS", p("L'Île aux Trésors")),
            Case("Le Jardin Secret.m4b", p("Le Jardin Secret")),
            Case("Le Jardin Secret.MP3", p("Le Jardin Secret")),
            Case("Le Jardin Secret - ", p("Le Jardin Secret")),
            Case("", p())
        )
    )

    @Test
    fun numbersThatAreNotTrackNumbers() = run(
        listOf(
            Case("11 22 33", p("11 22 33")),
            Case("11 22 33.m4b", p("11 22 33")),
            Case("Alice Durand - Fahrenheit 451", p("Fahrenheit 451", "Alice Durand")),
            Case("1984", p("1984")),
            Case("2001 L'odyssée", p("2001 L'odyssée")),
            Case("03. Le Jardin Secret", p("Le Jardin Secret"))
        )
    )

    @Test
    fun orderPrefixesAreDropped() = run(
        listOf(
            Case("1 - Planète Sable", p("Planète Sable")),
            Case("5 - Préludes", p("Préludes")),
            Case("03 - Le Jardin Secret", p("Le Jardin Secret")),
            Case("03 - Alice Durand - Le Jardin Secret", p("Le Jardin Secret", "Alice Durand")),
            Case("Hors-série", p("Hors-série"))
        )
    )

    @Test
    fun threeOrMoreDashParts() = run(
        listOf(
            Case("Alice Durand - Le Jardin - Première partie", p("Le Jardin - Première partie", "Alice Durand")),
            Case("Alice Durand - Cycle Sombre - 2 - La Suite", p("La Suite", "Alice Durand", "Cycle Sombre", 2.0))
        )
    )

    @Test
    fun groupingHintsPersonalDrive() = run(
        listOf(
            // The name says the series, the outermost grouping gives the author.
            Case(
                "La Saga des Brumes - 1 - Le Premier Pas.m4b",
                p("Le Premier Pas", "Auteur Exemple", "La Saga des Brumes", 1.0),
                listOf("Auteur Exemple", "La Saga des Brumes")
            ),
            // Title only: author from the outermost folder.
            Case("Un roman isolé.m4b", p("Un roman isolé", "Auteur Exemple"), listOf("Auteur Exemple")),
            // Title only in an inner unnumbered grouping: that grouping is the series.
            Case(
                "Départ.m4b",
                p("Départ", "Auteur Exemple", "La Grande Quête"),
                listOf("Auteur Exemple", "La Grande Quête")
            ),
            // Numbered sub-series folder: N is not a volume.
            Case(
                "Maison Une.m4b",
                p("Maison Une", "Auteur Exemple", "Préludes"),
                listOf("Auteur Exemple", "5 - Préludes")
            ),
            Case(
                "Préludes - 5.1 - Maison Une.m4b",
                p("Maison Une", "Auteur Exemple", "Préludes", 5.1),
                listOf("Auteur Exemple", "5 - Préludes")
            ),
            // The name wins over hints.
            Case(
                "Autre Auteur - Un roman.m4b",
                p("Un roman", "Autre Auteur"),
                listOf("Auteur Exemple")
            ),
            // Numbered grouping directly under the root: no author, series from the grouping.
            Case(
                "Planète Sable - 1 - Planète Sable.m4b",
                p("Planète Sable", null, "Planète Sable", 1.0),
                listOf("1 - Planète Sable")
            )
        )
    )

    @Test
    fun groupingHintsSharedDrive() = run(
        listOf(
            // AUTO: the outermost grouping equals the series read in the name, so it is not an author.
            Case(
                "Auteure Exemple - Série Exemple 2 - Le Second",
                p("Le Second", "Auteure Exemple", "Série Exemple", 2.0),
                listOf("Série Exemple")
            ),
            Case(
                "Auteure Exemple - Série Exemple 1",
                p("Série Exemple 1", "Auteure Exemple", "Série Exemple", 1.0),
                listOf("série exemple")
            ),
            // SERIES role: title-only book gets the series from the folder.
            Case("Le Second", p("Le Second", null, "Série Exemple"), listOf("Série Exemple"), GroupingRole.SERIES),
            // Series name in the book folder equal to the grouping: volume, no title.
            Case(
                "Série Exemple 4",
                p("Série Exemple 4", null, "Série Exemple", 4.0),
                listOf("Série Exemple"),
                GroupingRole.SERIES
            ),
            // SERIES role never invents an author.
            Case(
                "Alice Durand - Le Second",
                p("Le Second", "Alice Durand", "Série Exemple"),
                listOf("Série Exemple"),
                GroupingRole.SERIES
            ),
            // Explicit AUTHOR role.
            Case("Le Second", p("Le Second", "Auteur Exemple"), listOf("Auteur Exemple"), GroupingRole.AUTHOR)
        )
    )

    @Test
    fun orderPrefixHelper() {
        assertEquals("Préludes" to 5.0, NameParser.stripOrderPrefix("5 - Préludes"))
        assertEquals("Planète Sable" to 1.0, NameParser.stripOrderPrefix("1 - Planète Sable"))
        assertEquals("Auteur Exemple" to null, NameParser.stripOrderPrefix("Auteur Exemple"))
        assertEquals("11 22 33" to null, NameParser.stripOrderPrefix("11 22 33"))
    }

    @Test
    fun keyIgnoresCaseAccentsAndPunctuation() {
        assertEquals(NameParser.key("Verre"), NameParser.key("verre"))
        assertEquals(NameParser.key("Série, Exemple!"), NameParser.key("serie exemple"))
    }
}
