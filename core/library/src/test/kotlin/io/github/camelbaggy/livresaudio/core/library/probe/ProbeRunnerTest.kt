package io.github.camelbaggy.livresaudio.core.library.probe

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.camelbaggy.livresaudio.core.data.db.BookField
import io.github.camelbaggy.livresaudio.core.data.db.LibraryDatabase
import io.github.camelbaggy.livresaudio.core.data.identity.FieldMeta
import io.github.camelbaggy.livresaudio.core.data.identity.bookIdFor
import io.github.camelbaggy.livresaudio.core.data.repo.RoomLibraryRepository
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveError
import io.github.camelbaggy.livresaudio.core.library.chapters.CountingSource
import io.github.camelbaggy.livresaudio.core.library.chapters.resource
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.BookAnchor
import io.github.camelbaggy.livresaudio.core.model.BookOrigin
import io.github.camelbaggy.livresaudio.core.model.BookState
import io.github.camelbaggy.livresaudio.core.model.ByteRangeSource
import io.github.camelbaggy.livresaudio.core.model.DiscoveredBook
import io.github.camelbaggy.livresaudio.core.model.DriveItem
import io.github.camelbaggy.livresaudio.core.model.FieldSource
import io.github.camelbaggy.livresaudio.core.model.ParsedName
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class FakeSources(private val files: MutableMap<String, ByteArray> = mutableMapOf()) : ProbeSourceFactory {
    val failures = mutableMapOf<String, DriveError>()
    val opened = mutableListOf<String>()

    fun put(driveFileId: String, bytes: ByteArray) {
        files[driveFileId] = bytes
    }

    override fun open(accountId: String, driveFileId: String, sizeBytes: Long?): ByteRangeSource {
        opened += driveFileId
        val error = failures[driveFileId]
        if (error != null) {
            return object : ByteRangeSource {
                override suspend fun size() = sizeBytes

                override suspend fun read(offset: Long, length: Int): ByteArray = throw DriveReadException(error)
            }
        }
        return CountingSource(files.getValue(driveFileId))
    }
}

private class FakeCovers : CoverStore {
    val saved = mutableListOf<Triple<String, ByteArray, String?>>()

    override fun save(bookId: String, bytes: ByteArray, mimeType: String?): String? {
        saved += Triple(bookId, bytes, mimeType)
        return "file:///covers/$bookId.jpg"
    }
}

@RunWith(RobolectricTestRunner::class)
class ProbeRunnerTest {
    private lateinit var db: LibraryDatabase
    private lateinit var library: RoomLibraryRepository
    private lateinit var store: RoomProbeStore
    private val sources = FakeSources()
    private val covers = FakeCovers()
    private var now = 1_000_000L
    private lateinit var runner: ProbeRunner

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        library = RoomLibraryRepository(db, Dispatchers.Unconfined)
        store = RoomProbeStore(db, Dispatchers.Unconfined)
        runner = ProbeRunner(store, sources, AudioProbe(), covers, clock = { now })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun item(id: String, name: String, mime: String = "audio/mpeg") =
        DriveItem(id, name, mime, "folder", sizeBytes = 1_000, md5 = "md5-$id")

    private suspend fun addBook(
        folder: String,
        vararg files: DriveItem,
        parsedTitle: String? = "Titre du dossier"
    ): String {
        val discovered = DiscoveredBook(
            anchor = BookAnchor.Folder(folder),
            rootId = "root",
            files = files.toList(),
            groupingNames = emptyList(),
            parsed = ParsedName(title = parsedTitle),
            coverCandidates = emptyList()
        )
        return library.upsertDiscoveredBook(discovered, AccountId("acc"), BookOrigin.PERSONAL, now)
    }

    @Test
    fun livreDUnFichierM4bDureeChapitresEtiquettesEtCouverture() = runTest {
        val id = addBook("F1", item("f1", "livre.m4b", "audio/mp4"))
        sources.put("f1", resource("chpl-moov-fin.m4b"))

        val report = runner.run()

        assertEquals(1, report.booksProbed)
        assertEquals(1, report.filesProbed)
        assertFalse(report.retryLater)
        val b = assertNotNull(library.getBook(id))
        val file = b.files.single()
        assertTrue(file.probed)
        assertTrue(kotlin.math.abs(file.durationMs!! - 8_000) < 80)
        assertEquals(file.durationMs, b.book.totalDurationMs)
        assertEquals(3, b.chapters.size)
        assertEquals("Prologue : l'épée", b.chapters.first().title)
        assertEquals("Livre d'essai", b.book.title)
        assertEquals("Auteur Été", b.book.author)
        assertEquals(FieldSource.EMBEDDED, b.book.fieldSources[BookField.TITLE]?.source)
        assertEquals(now, b.book.fieldSources[BookField.TITLE]?.updatedAt)
    }

    @Test
    fun couvertureIntegreeExtraiteEtEnregistreeAvecProvenanceEmbedded() = runTest {
        val id = addBook("F1", item("f1", "livre.m4b", "audio/mp4"))
        sources.put("f1", probeResource("complet.m4b"))

        runner.run()

        val saved = covers.saved.single()
        assertEquals(id, saved.first)
        assertContentEquals(probeResource("cover.jpg"), saved.second)
        val b = assertNotNull(library.getBook(id)).book
        assertEquals("file:///covers/$id.jpg", b.coverUri)
        assertEquals(FieldSource.EMBEDDED, b.fieldSources[BookField.COVER_URI]?.source)
        assertEquals("Chapitre un", b.title) // un seul fichier : l'étiquette titre
        assertEquals("Narrateur Œil", b.narrator)
        assertEquals(2014, b.year)
    }

    @Test
    fun uneValeurUserSurvitAuSondage() = runTest {
        val id = addBook("F1", item("f1", "livre.m4b", "audio/mp4"))
        val book = db.bookDao().get(id)!!
        db.bookDao().upsert(
            book.copy(
                title = "Mon titre",
                fieldSources =
                    book.fieldSources + (BookField.TITLE to FieldMeta(FieldSource.USER, 5))
            )
        )
        sources.put("f1", probeResource("complet.m4b"))

        runner.run()

        val b = assertNotNull(library.getBook(id)).book
        assertEquals("Mon titre", b.title)
        assertEquals(FieldSource.USER, b.fieldSources[BookField.TITLE]?.source)
        assertEquals("Auteur Été", b.author)
    }

    @Test
    fun livreMultiFichiersOrdreParPistesTitreDeLAlbumDureeTotale() = runTest {
        // Noms dans l'ordre naturel a, b, c ; pistes inversées : c = piste 1 ...
        val id = addBook("F2", item("a", "a.mp3"), item("b", "b.mp3"), item("c", "c.mp3"))
        sources.put("a", probeResource("piste3.mp3"))
        sources.put("b", probeResource("piste2.mp3"))
        sources.put("c", probeResource("piste1.mp3"))

        val report = runner.run()

        assertEquals(1, report.booksReordered)
        val b = assertNotNull(library.getBook(id))
        assertEquals(listOf("c", "b", "a"), b.files.map { it.driveFileId })
        assertEquals(listOf(1, 2, 3), b.files.map { it.trackNumber })
        assertEquals("Album Multi", b.book.title)
        assertEquals("Auteur Multi", b.book.author)
        assertTrue(kotlin.math.abs(b.book.totalDurationMs!! - 6_000) < 300)
        assertEquals(b.files.sumOf { it.durationMs!! }, b.book.totalDurationMs)
        assertEquals(listOf(0, 1, 2), b.files.map { it.sortIndex })
    }

    @Test
    fun unRebalayageRemetLOrdreNaturelEtLeSondageSuivantLeCorrige() = runTest {
        val files = arrayOf(item("a", "a.mp3"), item("b", "b.mp3"), item("c", "c.mp3"))
        val id = addBook("F2", *files)
        sources.put("a", probeResource("piste3.mp3"))
        sources.put("b", probeResource("piste2.mp3"))
        sources.put("c", probeResource("piste1.mp3"))
        runner.run()

        addBook("F2", *files) // le parcours réécrit sortIndex dans l'ordre naturel
        assertEquals(listOf("a", "b", "c"), library.getBook(id)!!.files.map { it.driveFileId })
        sources.opened.clear()

        val report = runner.run()

        assertEquals(0, report.filesProbed) // rien à resonder : durées et pistes conservées
        assertTrue(sources.opened.isEmpty())
        assertEquals(1, report.booksReordered)
        assertEquals(listOf("c", "b", "a"), library.getBook(id)!!.files.map { it.driveFileId })
        // Un troisième passage ne change plus rien.
        assertEquals(0, runner.run().booksReordered)
    }

    @Test
    fun pistesIncoherentesGardentLOrdreNaturel() = runTest {
        val id = addBook("F3", item("a", "a.mp3"), item("b", "b.mp3"))
        sources.put("a", probeResource("cbr-info-v23.mp3")) // piste 3 disque 2
        sources.put("b", probeResource("vbr-xing.mp3")) // piste 3 disque 2 : doublon

        assertEquals(0, runner.run().booksReordered)
        assertEquals(listOf("a", "b"), library.getBook(id)!!.files.map { it.driveFileId })
    }

    @Test
    fun erreurReseauLaisseToutNonSondeEtDemandeUnNouvelEssai() = runTest {
        val id = addBook("F2", item("a", "a.mp3"), item("b", "b.mp3"))
        sources.put("a", probeResource("piste1.mp3"))
        sources.failures["b"] = DriveError.NetworkUnavailable

        val report = runner.run()

        assertTrue(report.retryLater)
        assertEquals(0, report.booksProbed)
        val b = assertNotNull(library.getBook(id))
        assertTrue(b.files.none { it.probed }, "rien n'est enregistré à moitié")
        assertEquals("Titre du dossier", b.book.title)

        sources.failures.clear()
        sources.put("b", probeResource("piste2.mp3"))
        val again = runner.run()
        assertFalse(again.retryLater)
        assertTrue(library.getBook(id)!!.files.all { it.probed })
    }

    @Test
    fun fichierIntrouvableEstMarqueSondeSansDuree() = runTest {
        val id = addBook("F1", item("a", "a.mp3"))
        sources.failures["a"] = DriveError.NotFound("disparu")

        val report = runner.run()

        assertFalse(report.retryLater)
        val b = assertNotNull(library.getBook(id))
        assertTrue(b.files.single().probed)
        assertNull(b.files.single().durationMs)
        assertNull(b.book.totalDurationMs)
        sources.opened.clear()
        runner.run()
        assertTrue(sources.opened.isEmpty(), "pas de reboucle")
    }

    @Test
    fun formatNonAnalysableEstMarqueSonde() = runTest {
        val id = addBook("F1", item("a", "a.ogg", "audio/ogg"))
        sources.put("a", "OggS".toByteArray() + ByteArray(100))

        runner.run()

        val f = library.getBook(id)!!.files.single()
        assertTrue(f.probed)
        assertNull(f.durationMs)
    }

    @Test
    fun lesInfosDuLivreNeViennentPasDUnFichierTardifSiLePremierEtaitDejaSonde() = runTest {
        val id = addBook("F2", item("a", "a.mp3"), item("b", "b.mp3"))
        sources.put("a", probeResource("piste1.mp3"))
        sources.put("b", probeResource("piste2.mp3"))
        // Le premier fichier a été sondé avant, sans que le livre ait reçu d'informations.
        val a = db.bookFileDao().getForBook(id).first { it.driveFileId == "a" }
        db.bookFileDao().setProbed(a.id, 2_000, 1, null)

        runner.run()

        assertEquals("Titre du dossier", library.getBook(id)!!.book.title)
    }

    @Test
    fun lesLivresManquantsSontIgnores() = runTest {
        val id = addBook("F1", item("a", "a.mp3"))
        db.bookDao().setState(listOf(id), BookState.MISSING, 5)

        val report = runner.run()

        assertEquals(0, report.filesProbed)
        assertTrue(sources.opened.isEmpty())
    }

    @Test
    fun unFichierDontLeMd5ChangePendantLeSondageResteNonSonde() = runTest {
        val id = addBook("F1", item("a", "a.mp3"))
        val file = db.bookFileDao().getForBook(id).single()
        sources.put("a", probeResource("piste1.mp3"))
        // Simule un parcours qui change le contenu entre la lecture et l'écriture : commit avec un ancien md5.
        store.commit(
            BookProbeCommit(
                id,
                listOf(FileProbeOutcome(file.id, "a", "ancien-md5", 1_000, 1, null, emptyList())),
                null,
                null,
                now
            )
        )
        assertFalse(db.bookFileDao().getForBook(id).single().probed)
    }

    @Test
    fun bookIdDeLAncreNEstPasModifie() = runTest {
        val id = addBook("F1", item("a", "a.mp3"))
        sources.put("a", probeResource("piste1.mp3"))
        runner.run()
        assertEquals(bookIdFor(BookAnchor.Folder("F1")), id)
        assertNotNull(library.getBook(id))
    }
}
