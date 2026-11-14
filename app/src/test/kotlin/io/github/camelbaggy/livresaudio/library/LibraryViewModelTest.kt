package io.github.camelbaggy.livresaudio.library

import io.github.camelbaggy.livresaudio.core.model.BookState
import io.github.camelbaggy.livresaudio.core.model.ListeningStatus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private lateinit var repo: FakeLibraryRepository
    private lateinit var gateway: FakeGateway
    private var scans = 0
    private lateinit var viewModel: LibraryViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repo = FakeLibraryRepository()
        gateway = FakeGateway()
        scans = 0
        viewModel = LibraryViewModel(repo, gateway) { scans++ }
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun kotlinx.coroutines.test.TestScope.collect() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect { } }
    }

    private fun titles() = viewModel.state.value.items.map { it.title }

    @Test
    fun `la recherche ignore la casse et les accents`() = runTest {
        collect()
        repo.rows.value = listOf(
            row(bookEntity("1", title = "L'Étranger", author = "Albert Camus")),
            row(bookEntity("2", title = "Dune", author = "Frank Herbert", narrator = "Éric Herson-Macarel")),
            row(bookEntity("3", title = "Fortune de France", series = "Fortune de France"))
        )
        viewModel.onQueryChange("etranger")
        assertEquals(listOf("L'Étranger"), titles())
        viewModel.onQueryChange("ÉRIC herson")
        assertEquals(listOf("Dune"), titles())
        viewModel.onQueryChange("camus etranger")
        assertEquals(listOf("L'Étranger"), titles())
        viewModel.onQueryChange("fortune")
        assertEquals(listOf("Fortune de France"), titles())
        viewModel.onQueryChange("zzz")
        assertTrue(titles().isEmpty())
        assertFalse(viewModel.state.value.libraryEmpty)
    }

    @Test
    fun `le tri par titre ignore les accents, par auteur met les sans-auteur a la fin`() = runTest {
        collect()
        repo.rows.value = listOf(
            row(bookEntity("1", title = "Zorro", author = "Isabel Allende")),
            row(bookEntity("2", title = "Émile", author = null)),
            row(bookEntity("3", title = "Eragon", author = "Paolini"))
        )
        assertEquals(listOf("Émile", "Eragon", "Zorro"), titles())
        viewModel.onSortChange(LibrarySort.AUTHOR)
        assertEquals(listOf("Zorro", "Eragon", "Émile"), titles())
    }

    @Test
    fun `tri par date d'ajout et par dernier ecoute`() = runTest {
        collect()
        repo.rows.value = listOf(
            row(bookEntity("a", title = "A", addedAt = 10), progressEntity("a", lastPlayedAt = 5)),
            row(bookEntity("b", title = "B", addedAt = 30)),
            row(bookEntity("c", title = "C", addedAt = 20), progressEntity("c", lastPlayedAt = 50))
        )
        viewModel.onSortChange(LibrarySort.ADDED)
        assertEquals(listOf("B", "C", "A"), titles())
        viewModel.onSortChange(LibrarySort.LAST_PLAYED)
        assertEquals(listOf("C", "A", "B"), titles())
    }

    @Test
    fun `le bandeau Reprendre montre le dernier livre en cours et disparait pendant une recherche`() = runTest {
        collect()
        repo.rows.value = listOf(
            row(
                bookEntity("a", title = "A", totalDurationMs = 1_000),
                progressEntity("a", lastPlayedAt = 5, bookPositionMs = 250)
            ),
            row(
                bookEntity("b", title = "B"),
                progressEntity("b", lastPlayedAt = 90, status = ListeningStatus.FINISHED)
            ),
            row(bookEntity("c", title = "C"), progressEntity("c", lastPlayedAt = 40)),
            row(bookEntity("d", title = "D"))
        )
        assertEquals("c", viewModel.state.value.resume?.bookId)
        viewModel.onQueryChange("a")
        assertNull(viewModel.state.value.resume)
        viewModel.onQueryChange("")
        viewModel.resume("c")
        assertEquals(listOf("playBook:c"), gateway.calls)
    }

    @Test
    fun `la progression est calculee sur la duree totale`() = runTest {
        collect()
        repo.rows.value = listOf(
            row(bookEntity("a", totalDurationMs = 1_000), progressEntity("a", bookPositionMs = 250)),
            row(bookEntity("b"), progressEntity("b", status = ListeningStatus.FINISHED))
        )
        val byId = viewModel.state.value.items.associateBy { it.bookId }
        assertEquals(0.25f, byId.getValue("a").progress)
        assertEquals(1f, byId.getValue("b").progress)
    }

    @Test
    fun `les livres MISSING sont masques`() = runTest {
        collect()
        repo.rows.value = listOf(
            row(bookEntity("a", title = "Présent")),
            row(bookEntity("b", title = "Disparu", state = BookState.MISSING), progressEntity("b", lastPlayedAt = 99))
        )
        assertEquals(listOf("Présent"), titles())
        assertNull(viewModel.state.value.resume)
    }

    @Test
    fun `etat vide et rafraichissement`() = runTest {
        collect()
        assertTrue(viewModel.state.value.loaded)
        assertTrue(viewModel.state.value.libraryEmpty)
        viewModel.refresh()
        assertEquals(1, scans)
        assertTrue(viewModel.state.value.refreshing)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.refreshing)
    }
}
