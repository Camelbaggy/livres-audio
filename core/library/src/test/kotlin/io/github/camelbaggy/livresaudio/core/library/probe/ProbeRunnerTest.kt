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
    var onOpen: (String) -> Unit = {}

    fun put(driveFileId: String, bytes: ByteArray) {
        files[driveFileId] = bytes
    }

    override fun open(accountId: String, driveFileId: String, sizeBytes: Long?): ByteRangeSource {
        opened += driveFileId
        onOpen(driveFileId)
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
    fun uneErreurReseauSurUnFichierGardeLeResteEtDemandeUnNouvelEssai() = runTest {
        val id = addBook("F2", item("a", "a.mp3"), item("b", "b.mp3"))
        sources.put("a", probeResource("piste1.mp3"))
        sources.failures["b"] = DriveError.NetworkUnavailable

        val report = runner.run()

        assertTrue(report.retryLater)
        assertTrue(report.hasRemainingWork)
        assertEquals(1, report.booksProbed) // la vitrine (premier fichier) est posée
        assertEquals(1, report.filesProbed)
        val b = assertNotNull(library.getBook(id))
        assertTrue(b.files.first { it.driveFileId == "a" }.probed)
        assertFalse(b.files.first { it.driveFileId == "b" }.probed, "le fichier en erreur reste à sonder")
        assertEquals("Album Multi", b.book.title)

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
        store.commitFile(
            FileProbeCommit(
                id,
                FileProbeOutcome(file.id, "a", "ancien-md5", 1_000, 1, null, emptyList()),
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

    // ---- Deux phases, priorités, robustesse --------------------------------------------------------------

    private fun twoFiles(folder: String) = addBookOf(folder, item("$folder-a", "a.mp3"), item("$folder-b", "b.mp3"))

    private fun addBookOf(folder: String, vararg files: DriveItem): String = kotlinx.coroutines.runBlocking {
        addBook(folder, *files)
    }

    @Test
    fun laVitrineDeTousLesLivresEstPoseAvantLesDetails() = runTest {
        val ids = (1..3).map { twoFiles("L$it") }
        for (n in 1..3) {
            sources.put("L$n-a", probeResource("piste1.mp3"))
            sources.put("L$n-b", probeResource("piste2.mp3"))
        }
        // Chaque ouverture de source coûte 10 ms ; le budget de 30 ms est épuisé à la fin de la vitrine.
        var clockMs = 0L
        sources.onOpen = { clockMs += 10 }
        val timed = ProbeRunner(store, sources, AudioProbe(), covers, clock = { now }, monotonicMs = { clockMs })

        val report = timed.run(timeBudgetMs = 30)

        assertEquals(3, report.booksProbed)
        assertEquals(0, report.filesProbed)
        assertTrue(report.timeUp)
        assertTrue(report.hasRemainingWork)
        ids.forEach { id ->
            val b = assertNotNull(library.getBook(id))
            assertEquals("Album Multi", b.book.title)
            assertEquals(FieldSource.EMBEDDED, b.book.fieldSources[BookField.TITLE]?.source)
            assertTrue(b.files.none { it.probed })
            assertTrue(db.bookFileDao().getForBook(id).first().tagsProbed)
        }

        // Une seconde passe, sans limite, termine sans rien refaire de la vitrine.
        sources.opened.clear()
        val rest = ProbeRunner(store, sources, AudioProbe(), covers, clock = { now }).run()
        assertEquals(0, rest.booksProbed)
        assertEquals(6, rest.filesProbed)
        assertFalse(rest.hasRemainingWork)
        assertTrue(ids.all { id -> library.getBook(id)!!.files.all { it.probed } })
    }

    @Test
    fun leLivreEnCoursEstSondeEnPremier() = runTest {
        val a = addBook("A", item("fa", "a.mp3"))
        val b = addBook("B", item("fb", "b.mp3"))
        val c = addBook("C", item("fc", "c.mp3"))
        listOf("fa", "fb", "fc").forEach { sources.put(it, probeResource("piste1.mp3")) }
        db.progressDao().upsert(progress(b, lastPlayedAt = 50))
        db.progressDao().upsert(progress(c, lastPlayedAt = 900))

        runner.run()

        assertEquals(listOf("fc", "fb", "fa"), sources.opened.take(3)) // vitrine : récent d'abord
        assertEquals(a, bookIdFor(BookAnchor.Folder("A")))
    }

    private fun progress(bookId: String, lastPlayedAt: Long) =
        io.github.camelbaggy.livresaudio.core.data.db.ProgressEntity(
            bookId = bookId,
            driveFileId = "x",
            fileMd5 = null,
            fileIndex = 0,
            positionMs = 1_000,
            bookPositionMs = 1_000,
            status = io.github.camelbaggy.livresaudio.core.model.ListeningStatus.IN_PROGRESS,
            speed = null,
            lastPlayedAt = lastPlayedAt,
            updatedAt = lastPlayedAt,
            dirty = false
        )

    @Test
    fun uneErreurSurUnLivreNeStoppePasLesAutres() = runTest {
        val ids = (1..3).map { addBook("L$it", item("f$it", "l$it.mp3")) }
        listOf("f1", "f3").forEach { sources.put(it, probeResource("piste1.mp3")) }
        sources.failures["f2"] = DriveError.Server(500)

        val report = runner.run()

        assertTrue(report.retryLater)
        assertTrue(report.progressed)
        assertTrue(library.getBook(ids[0])!!.files.single().probed)
        assertFalse(library.getBook(ids[1])!!.files.single().probed)
        assertTrue(library.getBook(ids[2])!!.files.single().probed)

        sources.failures.clear()
        sources.put("f2", probeResource("piste1.mp3"))
        assertFalse(runner.run().retryLater)
        assertTrue(library.getBook(ids[1])!!.files.single().probed)
    }

    @Test
    fun unCompteQuiEchoueSouventDeSuiteEstLaisseAuRunSuivant() = runTest {
        repeat(10) { n ->
            addBook("L$n", item("f$n", "l$n.mp3"))
            sources.failures["f$n"] = DriveError.NetworkUnavailable
        }

        val report = runner.run()

        assertTrue(report.retryLater)
        assertFalse(report.progressed)
        assertEquals(ProbeRunner.MAX_CONSECUTIVE_FAILURES, sources.opened.size)
    }

    @Test
    fun sansReseauRienNEstTente() = runTest {
        addBook("F1", item("a", "a.mp3"))
        val offline = ProbeRunner(store, sources, AudioProbe(), covers, networkAvailable = { false })

        val report = offline.run()

        assertTrue(report.retryLater)
        assertTrue(sources.opened.isEmpty())
    }

    @Test
    fun surReseauMesureSeuleLaVitrineEstFaite() = runTest {
        addBook("A", item("fa", "a.mp3"), item("fb", "b.mp3"))
        sources.put("fa", probeResource("piste1.mp3"))
        sources.put("fb", probeResource("piste1.mp3"))
        val metered = ProbeRunner(store, sources, AudioProbe(), covers, clock = { now }, detailsAllowed = { false })

        val report = metered.run()

        assertEquals(1, report.booksProbed)
        assertEquals(0, report.filesProbed)
        assertTrue(report.retryLater)
        assertTrue(sources.opened.none { it == "fb" })
    }

    @Test
    fun surDemandeUnSeulLivreEstSonde() = runTest {
        val a = addBook("A", item("fa", "a.mp3"))
        addBook("B", item("fb", "b.mp3"))
        sources.put("fa", probeResource("piste1.mp3"))
        sources.put("fb", probeResource("piste1.mp3"))

        val report = runner.run(onlyBookId = a)

        assertEquals(1, report.booksProbed)
        assertEquals(1, report.filesProbed)
        assertTrue(sources.opened.all { it == "fa" })
    }

    @Test
    fun lesChapitresDesFichiersSontNumerotesDansLOrdreDuLivreMemeSiLesFichiersArriventSepares() = runTest {
        val id = addBook("M", item("a", "a.m4b", "audio/mp4"), item("b", "b.m4b", "audio/mp4"))
        sources.put("a", resource("chpl-moov-fin.m4b"))
        sources.put("b", resource("chpl-moov-fin.m4b"))
        val files = db.bookFileDao().getForBook(id)

        // Le second fichier est validé avant le premier : la numérotation doit quand même suivre l'ordre du livre.
        for (name in listOf("b", "a")) {
            val f = files.first { it.driveFileId == name }
            val chapters = listOf(
                io.github.camelbaggy.livresaudio.core.model.EmbeddedChapter("$name 1", 0, 1_000),
                io.github.camelbaggy.livresaudio.core.model.EmbeddedChapter("$name 2", 1_000, 2_000)
            )
            store.commitFile(
                FileProbeCommit(
                    id,
                    FileProbeOutcome(f.id, f.driveFileId, f.md5, 2_000, null, null, chapters),
                    null,
                    null,
                    now
                )
            )
        }

        val chapters = db.chapterDao().getForBook(id)
        assertEquals(listOf("a 1", "a 2", "b 1", "b 2"), chapters.map { it.title })
        assertEquals(listOf(0, 1, 2, 3), chapters.map { it.indexInBook })
    }

    @Test
    fun laVitrineNeToucheNiAuxChapitresNiAuStatutSonde() = runTest {
        val id = addBook("F1", item("f1", "livre.m4b", "audio/mp4"))
        sources.put("f1", resource("chpl-moov-fin.m4b"))
        var clockMs = 0L
        sources.onOpen = { clockMs += 1_000 }
        val timed = ProbeRunner(store, sources, AudioProbe(), covers, clock = { now }, monotonicMs = { clockMs })

        timed.run(timeBudgetMs = 500) // la vitrine seule

        val b = assertNotNull(library.getBook(id))
        assertFalse(b.files.single().probed)
        assertTrue(b.chapters.isEmpty())
        assertTrue(kotlin.math.abs(b.files.single().durationMs!! - 8_000) < 80) // durée de l'en-tête
        assertEquals(b.files.single().durationMs, b.book.totalDurationMs)
        assertEquals("Livre d'essai", b.book.title)
    }
}
