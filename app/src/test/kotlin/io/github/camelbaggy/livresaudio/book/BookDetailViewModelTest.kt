package io.github.camelbaggy.livresaudio.book

import androidx.lifecycle.SavedStateHandle
import io.github.camelbaggy.livresaudio.core.data.db.BookWithFiles
import io.github.camelbaggy.livresaudio.core.model.ListeningStatus
import io.github.camelbaggy.livresaudio.core.playback.ActiveBook
import io.github.camelbaggy.livresaudio.core.playback.ChapterTimeline
import io.github.camelbaggy.livresaudio.core.playback.PlaybackBook
import io.github.camelbaggy.livresaudio.core.playback.PlaybackFile
import io.github.camelbaggy.livresaudio.library.FakeActiveBookSource
import io.github.camelbaggy.livresaudio.library.FakeGateway
import io.github.camelbaggy.livresaudio.library.FakeLibraryRepository
import io.github.camelbaggy.livresaudio.library.FakeProgressRepository
import io.github.camelbaggy.livresaudio.library.bookEntity
import io.github.camelbaggy.livresaudio.library.bookFile
import io.github.camelbaggy.livresaudio.library.progressEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailViewModelTest {
    private lateinit var library: FakeLibraryRepository
    private lateinit var progress: FakeProgressRepository
    private lateinit var gateway: FakeGateway
    private lateinit var active: FakeActiveBookSource
    private lateinit var viewModel: BookDetailViewModel
    private val probed = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        library = FakeLibraryRepository()
        progress = FakeProgressRepository()
        gateway = FakeGateway()
        active = FakeActiveBookSource()
        library.books.value = mapOf(
            "b1" to BookWithFiles(
                book = bookEntity("b1", title = "Titre", totalDurationMs = 120_000),
                files = listOf(bookFile("b1", 0), bookFile("b1", 1)),
                chapters = emptyList()
            )
        )
        progress.current.value = progressEntity("b1", positionMs = 5_000, bookPositionMs = 65_000)
        viewModel = BookDetailViewModel(
            SavedStateHandle(mapOf(BookDetailViewModel.ARG_BOOK_ID to "b1")),
            library,
            progress,
            gateway,
            active,
            Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)
        ) { probed += it }
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun kotlinx.coroutines.test.TestScope.collect() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect { } }
    }

    private fun loadedActive() = ActiveBook(
        PlaybackBook("b1", "acc", "Titre", null, null, listOf(PlaybackFile("f", null, 60_000, "n")), emptyList()),
        ChapterTimeline(listOf(PlaybackFile("f", null, 60_000, "n")), emptyList())
    )

    @Test
    fun `la fiche montre la progression et les fichiers comme chapitres`() = runTest {
        collect()
        val state = viewModel.state.value
        assertEquals("Titre", state.title)
        assertTrue(state.canResume)
        assertEquals(2, state.chapters.size)
        assertEquals(65_000f / 120_000f, state.progress)
    }

    @Test
    fun `la remise a zero exige une confirmation`() = runTest {
        collect()
        viewModel.requestReset()
        assertTrue(viewModel.state.value.confirmReset)
        assertTrue(progress.saved.isEmpty())
        viewModel.dismissReset()
        assertFalse(viewModel.state.value.confirmReset)
        assertTrue(progress.saved.isEmpty())
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `remise a zero confirmee d'un livre non charge ecrit la position avec allowReset`() = runTest {
        collect()
        viewModel.requestReset()
        viewModel.confirmReset()
        assertEquals(
            listOf(FakeProgressRepository.Saved("b1", 0, ListeningStatus.NOT_STARTED, allowReset = true)),
            progress.saved
        )
        assertFalse(viewModel.state.value.confirmReset)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `remise a zero confirmee d'un livre charge passe par la commande du service`() = runTest {
        collect()
        active.active.value = loadedActive()
        assertTrue(viewModel.state.value.loadedInPlayer)
        viewModel.requestReset()
        viewModel.confirmReset()
        assertEquals(listOf("reset"), gateway.calls)
        assertTrue(progress.saved.isEmpty())
    }

    @Test
    fun `marquer termine garde la position`() = runTest {
        collect()
        viewModel.markFinished()
        assertEquals(
            listOf(FakeProgressRepository.Saved("b1", 5_000, ListeningStatus.FINISHED, allowReset = false)),
            progress.saved
        )
    }

    @Test
    fun `lire charge le livre`() = runTest {
        collect()
        viewModel.play()
        assertEquals(listOf("playBook:b1"), gateway.calls)
    }

    @Test
    fun `ouvrir la fiche d'un livre non sonde demande un sondage prioritaire`() = runTest {
        library.books.value = mapOf(
            "b2" to BookWithFiles(
                book = bookEntity("b2", title = "Autre"),
                files = listOf(bookFile("b2", 0, durationMs = null, probed = false)),
                chapters = emptyList()
            )
        )
        BookDetailViewModel(
            SavedStateHandle(mapOf(BookDetailViewModel.ARG_BOOK_ID to "b2")),
            library,
            progress,
            gateway,
            active,
            Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)
        ) { probed += it }

        assertEquals(listOf("b2"), probed)
    }

    @Test
    fun `un livre deja sonde ne demande rien`() = runTest {
        assertTrue(probed.isEmpty())
    }
}
