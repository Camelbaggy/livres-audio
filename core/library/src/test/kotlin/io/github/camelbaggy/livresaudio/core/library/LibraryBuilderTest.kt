package io.github.camelbaggy.livresaudio.core.library

import io.github.camelbaggy.livresaudio.core.model.BookAnchor
import io.github.camelbaggy.livresaudio.core.model.DiscoveredBook
import io.github.camelbaggy.livresaudio.core.model.DriveItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Trees are invented and reproduce the shapes of docs/formes-de-noms.md, cases 1 to 4. */
class LibraryBuilderTest {
    private fun build(t: TestTree, role: GroupingRole = GroupingRole.AUTO) =
        LibraryBuilder().build(t.rootId, t.items, role)

    private fun DiscoveredBook.fileNames() = files.map { it.name }

    private fun List<DiscoveredBook>.byTitle(title: String) = single { it.parsed.title == title }

    // region case 1

    @Test
    fun case1OneFilePerBookInASeriesFolder() {
        val t = TestTree()
        t.folder("Auteur Exemple") { author ->
            t.folder("La Saga des Brumes", author) { s ->
                t.files(
                    s,
                    "La Saga des Brumes - 1 - Le Premier Pas.m4b",
                    "La Saga des Brumes - 0 - Avant le commencement.m4b",
                    "La Saga des Brumes - 10 - Le Retour.mp3",
                    "La Saga des Brumes - 3.5 - Une nouvelle.m4b",
                    "description.txt",
                    "Suivi.gsheet"
                )
            }
        }
        val books = build(t)
        assertEquals(4, books.size)
        books.forEach {
            assertIs<BookAnchor.SingleFile>(it.anchor)
            assertEquals(1, it.files.size)
            assertEquals(listOf("Auteur Exemple", "La Saga des Brumes"), it.groupingNames)
            assertEquals("Auteur Exemple", it.parsed.author)
            assertEquals("La Saga des Brumes", it.parsed.seriesName)
        }
        val indexes: List<Double> = books.mapNotNull { it.parsed.seriesIndex }.sorted()
        assertEquals(listOf(0.0, 1.0, 3.5, 10.0), indexes)
        val b = books.byTitle("Une nouvelle")
        assertEquals(3.5, b.parsed.seriesIndex)
        assertEquals(b.files.single().id, (b.anchor as BookAnchor.SingleFile).fileId)
        assertEquals(
            books.first {
                it.parsed.seriesIndex == 1.0
            }.files.single().parentId,
            (b.anchor as BookAnchor.SingleFile).parentFolderId
        )
    }

    @Test
    fun case1SeriesWithoutTitles() {
        val t = TestTree()
        t.folder("Auteur Exemple") { a ->
            t.folder("Série Exemple", a) { s -> t.files(s, "Série Exemple - 1.m4b", "Série Exemple - 2.m4b") }
        }
        val books = build(t)
        assertEquals(listOf("Série Exemple 1", "Série Exemple 2"), books.mapNotNull { it.parsed.title }.sorted())
        assertTrue(books.all { it.anchor is BookAnchor.SingleFile })
    }

    // endregion

    // region case 2

    @Test
    fun case2AuthorFolderMixingIsolatedBooksAndSeries() {
        val t = TestTree()
        t.folder("Auteur Exemple") { a ->
            t.files(
                a,
                "Un roman isolé.m4b",
                "Autre Roman.mp3",
                "11 22 33.m4b",
                "Cycle Sombre - 2 - La Suite.m4b",
                "Cycle Sombre - 1 - Le Début.m4b",
                "description.txt",
                "Suivi.gsheet"
            )
            t.folder("La Grande Quête", a) { q -> t.file("La Grande Quête - 1 - Départ.m4b", q) }
        }
        val books = build(t)
        assertEquals(6, books.size)
        assertTrue(books.all { it.anchor is BookAnchor.SingleFile })
        assertTrue(books.all { it.files.size == 1 })
        assertTrue(books.all { it.parsed.author == "Auteur Exemple" })

        assertEquals(null, books.byTitle("Un roman isolé").parsed.seriesName)
        assertEquals(listOf("Auteur Exemple"), books.byTitle("Un roman isolé").groupingNames)
        books.byTitle("11 22 33").also {
            assertEquals(null, it.parsed.seriesIndex)
            assertEquals("11 22 33.m4b", it.files.single().name)
        }
        assertEquals("Cycle Sombre" to 2.0, books.byTitle("La Suite").parsed.let { it.seriesName to it.seriesIndex })
        assertEquals("Cycle Sombre" to 1.0, books.byTitle("Le Début").parsed.let { it.seriesName to it.seriesIndex })
        // The one-file series folder is read as a single-file book from its structured name.
        books.byTitle("Départ").also {
            assertEquals("La Grande Quête" to 1.0, it.parsed.seriesName to it.parsed.seriesIndex)
            assertEquals(listOf("Auteur Exemple", "La Grande Quête"), it.groupingNames)
        }
    }

    // endregion

    // region case 3

    @Test
    fun case3NumberedSubSeries() {
        val t = TestTree()
        t.folder("Auteur Exemple") { a ->
            t.folder("1 - Planète Sable", a) { s -> t.file("Planète Sable - 1 - Planète Sable.m4b", s) }
            t.folder("5 - Préludes", a) { s ->
                t.files(
                    s,
                    "Préludes - 5.2 - Maison Deux.m4b",
                    "Préludes - 5.1 - Maison Une.m4b",
                    "Préludes - 5.10 - Maison Dix.m4b"
                )
            }
        }
        val books = build(t)
        assertEquals(4, books.size)
        assertTrue(books.all { it.anchor is BookAnchor.SingleFile })
        books.byTitle("Planète Sable").also {
            assertEquals("Planète Sable" to 1.0, it.parsed.seriesName to it.parsed.seriesIndex)
            assertEquals(listOf("Auteur Exemple", "1 - Planète Sable"), it.groupingNames)
        }
        // 5.1 is "sub-series 5, volume 1": numbers are kept as written, the folder prefix is not a volume.
        assertEquals(5.1, books.byTitle("Maison Une").parsed.seriesIndex)
        assertEquals(5.2, books.byTitle("Maison Deux").parsed.seriesIndex)
        assertTrue(books.all { it.parsed.seriesName in setOf("Planète Sable", "Préludes") })
    }

    @Test
    fun case3TitleOnlyFileInNumberedFolderTakesTheSubSeries() {
        val t = TestTree()
        t.folder("Auteur Exemple") { a -> t.folder("5 - Préludes", a) { s -> t.file("Maison Une.m4b", s) } }
        val book = build(t).single()
        assertIs<BookAnchor.SingleFile>(book.anchor)
        assertEquals("Préludes", book.parsed.seriesName)
        assertEquals(null, book.parsed.seriesIndex)
        assertEquals("Auteur Exemple", book.parsed.author)
    }

    // endregion

    // region case 4

    private fun case4Tree(): TestTree {
        val t = TestTree()
        t.folder("Série Exemple") { s ->
            t.folder("Auteure Exemple - Série Exemple 1", s) { b ->
                t.files(
                    b,
                    "01 - Chapitre 1.mp3",
                    "00 - Prologue.mp3",
                    "10 - Chapitre 10.mp3",
                    "02 - Chapitre 2.mp3",
                    "81 - Remerciements.mp3"
                )
                t.file("cover.jpg", b, size = 5)
                t.file("autre.png", b, size = 50)
            }
            t.folder("Auteure Exemple - Série Exemple 2 - Le Second", s) { b ->
                t.files(
                    b,
                    "15 Le Second - Auteure Exemple Chapitre 14.mp3",
                    "01 Le Second - Auteure Exemple Présentation.mp3",
                    "16 Le Second - Auteure Exemple Chapitre 15.mp3"
                )
            }
            t.folder("Auteure Exemple - Série Exemple 3 5 - Le Hors-série", s) { b ->
                t.files(
                    b,
                    "07 Auteure Exemple Le Hors-série Chapitre 07.mp3",
                    "08 Auteure Exemple Le Hors-série Chapitre 08.mp3"
                )
            }
            t.folder("Auteure Exemple - Série Exemple 0 - Les Origines", s) { b ->
                t.files(
                    b,
                    "002-Chapitre 2-2.mp3",
                    "002-Chapitre 2-1.mp3",
                    "010-Chapitre 10.mp3",
                    "141 - Chapitre 141.mp3"
                )
            }
            t.folder("Auteure Exemple - verre 4 - Le Quatrième", s) { b ->
                t.files(
                    b,
                    "Auteure Exemple - verre 4- Le Quatrième Chapitre 10.mp3",
                    "Auteure Exemple - verre 4- Le Quatrième Chapitre 05.mp3"
                )
            }
            t.folder("Le Sixième_ Série Exemple, tome 6", s) { b ->
                t.files(
                    b,
                    "Le Sixième_ Série Exemple, tome 6 - 10 - Chapitre 10.mp3",
                    "Le Sixième_ Série Exemple, tome 6 - 09 - Chapitre 9.mp3"
                )
            }
        }
        return t
    }

    @Test
    fun case4OneFolderPerBook() {
        val books = build(case4Tree(), GroupingRole.SERIES)
        assertEquals(6, books.size)
        books.forEach {
            assertIs<BookAnchor.Folder>(it.anchor)
            assertEquals(listOf("Série Exemple"), it.groupingNames)
        }
        books.single { it.parsed.seriesIndex == 3.5 }.also {
            assertEquals("Le Hors-série", it.parsed.title)
            assertEquals("Auteure Exemple", it.parsed.author)
            assertEquals("Série Exemple", it.parsed.seriesName)
            assertEquals(2, it.files.size)
        }
        books.single { it.parsed.seriesIndex == 0.0 }.also {
            assertEquals("Les Origines", it.parsed.title)
            assertEquals(
                listOf("002-Chapitre 2-1.mp3", "002-Chapitre 2-2.mp3", "010-Chapitre 10.mp3", "141 - Chapitre 141.mp3"),
                it.fileNames()
            )
        }
        books.single { it.parsed.seriesIndex == 6.0 }.also {
            assertEquals("Le Sixième", it.parsed.title)
            assertEquals("Série Exemple", it.parsed.seriesName)
            assertEquals(null, it.parsed.author)
            assertEquals(
                listOf(
                    "Le Sixième_ Série Exemple, tome 6 - 09 - Chapitre 9.mp3",
                    "Le Sixième_ Série Exemple, tome 6 - 10 - Chapitre 10.mp3"
                ),
                it.fileNames()
            )
        }
        books.single { it.parsed.title == "Le Quatrième" }.also {
            assertEquals("Verre", it.parsed.seriesName)
            assertEquals(4.0, it.parsed.seriesIndex)
            assertTrue(it.fileNames().first().endsWith("Chapitre 05.mp3"))
        }
    }

    @Test
    fun case4ChaptersOrderedAndCoversRanked() {
        val books = build(case4Tree(), GroupingRole.SERIES)
        val first = books.single { it.parsed.seriesIndex == 1.0 }
        assertEquals("Série Exemple 1", first.parsed.title)
        assertEquals(
            listOf(
                "00 - Prologue.mp3",
                "01 - Chapitre 1.mp3",
                "02 - Chapitre 2.mp3",
                "10 - Chapitre 10.mp3",
                "81 - Remerciements.mp3"
            ),
            first.fileNames()
        )
        assertEquals(listOf("cover.jpg", "autre.png"), first.coverCandidates.map { it.name })
        val second = books.single { it.parsed.seriesIndex == 2.0 }
        assertEquals("01 Le Second - Auteure Exemple Présentation.mp3", second.files.first().name)
    }

    @Test
    fun case4WithAutoRoleDoesNotTurnTheSeriesFolderIntoAnAuthor() {
        val books = build(case4Tree())
        val inSeries = books.filter { it.parsed.seriesName == "Série Exemple" }
        assertEquals(5, inSeries.size)
        assertTrue(inSeries.all { it.parsed.author != "Série Exemple" })
        assertEquals(4, inSeries.count { it.parsed.author == "Auteure Exemple" })
    }

    // endregion

    // region other rules

    @Test
    fun discFoldersMakeOneBook() {
        val t = TestTree()
        t.folder("Auteur Exemple") { a ->
            t.folder("Auteur Exemple - Roman en deux disques", a) { b ->
                t.folder("CD 2", b) { d -> t.files(d, "02 - b.mp3", "01 - a.mp3") }
                t.folder("CD 10", b) { d -> t.files(d, "01 - z.mp3") }
                t.folder("CD 1", b) { d ->
                    t.files(d, "10 - j.mp3", "2 - i.mp3")
                    t.file("cover.jpg", d)
                }
            }
        }
        val book = build(t).single()
        assertIs<BookAnchor.Folder>(book.anchor)
        assertEquals(listOf("2 - i.mp3", "10 - j.mp3", "01 - a.mp3", "02 - b.mp3", "01 - z.mp3"), book.fileNames())
        assertEquals("Roman en deux disques", book.parsed.title)
        assertEquals(listOf("Auteur Exemple"), book.groupingNames)
        assertEquals(listOf("cover.jpg"), book.coverCandidates.map { it.name })
    }

    @Test
    fun mixedFoldersAreNotDiscs() {
        val t = TestTree()
        t.folder("Regroupement") { r ->
            t.folder("CD 1", r) { d -> t.file("a.mp3", d) }
            t.folder("Bonus", r) { d -> t.file("b.mp3", d) }
        }
        assertEquals(2, build(t).size)
    }

    @Test
    fun rootAudioFilesAreSingleFileBooks() {
        val t = TestTree()
        t.files(t.rootId, "Roman Seul.m4b", "Autre Roman.mp3")
        val books = build(t)
        assertEquals(2, books.size)
        assertTrue(books.all { it.anchor is BookAnchor.SingleFile && it.groupingNames.isEmpty() })
    }

    @Test
    fun wmaIsListedAsUnsupported() {
        val t = TestTree()
        t.folder("Livre") { f -> t.files(f, "01.mp3", "02.wma") }
        t.folder("Vieux") { f -> t.file("seul.wma", f) }
        val books = build(t)
        val mixed = books.single { it.parsed.title == "Livre" }
        assertEquals(listOf("01.mp3"), mixed.fileNames())
        assertEquals(listOf("02.wma"), mixed.unsupportedFiles.map { it.name })
        val old = books.single { it.parsed.title == "Vieux" }
        assertEquals(emptyList(), old.files)
        assertEquals(1, old.unsupportedFiles.size)
    }

    @Test
    fun foldersWithoutAudioAreIgnored() {
        val t = TestTree()
        t.folder("Documents") { d ->
            t.file("notes.txt", d)
            t.folder("Photos", d) { p -> t.file("a.jpg", p) }
        }
        assertEquals(emptyList(), build(t))
    }

    @Test
    fun singleChapterFolderKeepsTheFolderAsBook() {
        val t = TestTree()
        t.folder("Nouvelle Courte") { f -> t.file("01.mp3", f) }
        val book = build(t).single()
        assertIs<BookAnchor.Folder>(book.anchor)
        assertEquals("Nouvelle Courte", book.parsed.title)
    }

    @Test
    fun parentIdCyclesTerminate() {
        val t = TestTree()
        t.items += DriveItem("A", "A", DriveItem.FOLDER_MIME_TYPE, "B")
        t.items += DriveItem("B", "B", DriveItem.FOLDER_MIME_TYPE, "A")
        t.items += DriveItem("A-f", "x.mp3", "audio/mpeg", "A")
        t.items += DriveItem("B-f", "y.mp3", "audio/mpeg", "B")
        assertEquals(emptyList(), build(t))
        // A cycle that includes the root.
        t.items += DriveItem("loop", "loop", DriveItem.FOLDER_MIME_TYPE, "B")
        t.items += DriveItem("root-in-cycle", "R", DriveItem.FOLDER_MIME_TYPE, "loop")
        build(t)
    }

    @Test
    fun deepTreesAreCutAtTheDepthLimit() {
        val t = TestTree()
        var parent = t.rootId
        repeat(30) { i -> parent = t.folder("niveau $i", parent) }
        t.file("Trop profond.mp3", parent)
        assertEquals(emptyList(), LibraryBuilder(maxDepth = 12).build(t.rootId, t.items))
        assertEquals(1, LibraryBuilder(maxDepth = 40).build(t.rootId, t.items).size)
    }

    @Test
    fun customFileOrderingIsUsed() {
        val t = TestTree()
        t.folder("Livre") { f -> t.files(f, "Chapitre 1.mp3", "Chapitre 2.mp3") }
        val reversed = FileOrdering { files -> NaturalFileOrdering.order(files).reversed() }
        val book = LibraryBuilder(fileOrdering = reversed).build(t.rootId, t.items).single()
        assertEquals(listOf("Chapitre 2.mp3", "Chapitre 1.mp3"), book.fileNames())
    }

    @Test
    fun buildIsDeterministicWhateverTheListingOrder() {
        val t = case4Tree()
        val a = LibraryBuilder().build(t.rootId, t.items, GroupingRole.SERIES)
        val b = LibraryBuilder().build(t.rootId, t.items.shuffled(java.util.Random(7)), GroupingRole.SERIES)
        assertEquals(a, b)
    }

    // endregion
}
