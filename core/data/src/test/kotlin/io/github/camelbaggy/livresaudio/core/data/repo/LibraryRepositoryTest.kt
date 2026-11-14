package io.github.camelbaggy.livresaudio.core.data.repo

import io.github.camelbaggy.livresaudio.core.data.db.AnchorKind
import io.github.camelbaggy.livresaudio.core.data.db.BookField
import io.github.camelbaggy.livresaudio.core.data.db.ChapterEntity
import io.github.camelbaggy.livresaudio.core.data.identity.FieldMeta
import io.github.camelbaggy.livresaudio.core.data.identity.bookIdFor
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.BookAnchor
import io.github.camelbaggy.livresaudio.core.model.BookOrigin
import io.github.camelbaggy.livresaudio.core.model.BookState
import io.github.camelbaggy.livresaudio.core.model.DiscoveredBook
import io.github.camelbaggy.livresaudio.core.model.DriveItem
import io.github.camelbaggy.livresaudio.core.model.FieldSource
import io.github.camelbaggy.livresaudio.core.model.ListeningStatus
import io.github.camelbaggy.livresaudio.core.model.ParsedName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class LibraryRepositoryTest : RepoTestBase() {
    private val repo by lazy { RoomLibraryRepository(db, dispatcher) }
    private val progress by lazy { RoomProgressRepository(db, dispatcher) }
    private val acc = AccountId("acc1")

    private fun audio(id: String, md5: String, size: Long = 100) =
        DriveItem(id, "$id.mp3", "audio/mpeg", "folder1", size, md5, 1L)

    private fun folderBook(
        vararg files: DriveItem,
        parsed: ParsedName = ParsedName(title = "Le Titre", author = "Auteur Exemple")
    ) = DiscoveredBook(
        BookAnchor.Folder("folder1"),
        "root1",
        files.toList(),
        listOf("Auteur Exemple"),
        parsed,
        emptyList()
    )

    @Test
    fun upsertCreatesFolderBookWithFilesInOrder() = runTest {
        val id = repo.upsertDiscoveredBook(
            folderBook(audio("f2", "m2", 50), audio("f1", "m1", 70)),
            acc,
            BookOrigin.SHARED,
            1000
        )
        assertEquals(bookIdFor(BookAnchor.Folder("folder1")), id)
        val b = assertNotNull(repo.getBook(id))
        assertEquals(AnchorKind.FOLDER, b.book.anchorKind)
        assertEquals("folder1", b.book.anchorDriveId)
        assertEquals("folder1", b.book.driveFolderId)
        assertEquals(BookOrigin.SHARED, b.book.origin)
        assertEquals(120L, b.book.totalBytes)
        assertEquals(1000L, b.book.addedAt)
        assertEquals(listOf("f2", "f1"), b.files.map { it.driveFileId })
        assertEquals(listOf(0, 1), b.files.map { it.sortIndex })
        assertEquals("Le Titre", b.book.title)
        assertEquals(FieldMeta(FieldSource.FOLDER, 1000), b.book.fieldSources[BookField.TITLE])
        assertEquals(1, repo.observeLibrary().first().size)
    }

    @Test
    fun upsertSingleFileBookAnchorsOnFileNotParent() = runTest {
        val file = DriveItem("fileA", "Serie - 1 - Titre.m4b", "audio/mp4", "parentP", 500, "mA", 1L)
        val discovered = DiscoveredBook(
            BookAnchor.SingleFile("fileA", "parentP"),
            "root1",
            listOf(file),
            emptyList(),
            ParsedName(title = "Titre", seriesName = "Serie", seriesIndex = 1.0),
            emptyList()
        )
        val id = repo.upsertDiscoveredBook(discovered, acc, BookOrigin.PERSONAL, 5)
        val b = assertNotNull(repo.getBook(id))
        assertEquals(AnchorKind.SINGLE_FILE, b.book.anchorKind)
        assertEquals("fileA", b.book.anchorDriveId)
        assertEquals("parentP", b.book.driveFolderId)
        assertEquals(1.0, b.book.seriesIndex)
        assertEquals(1, b.files.size)
    }

    @Test
    fun reUpsertKeepsFileIdsProbeResultsAndAddedAt() = runTest {
        val id = repo.upsertDiscoveredBook(folderBook(audio("f1", "m1"), audio("f2", "m2")), acc, BookOrigin.SHARED, 10)
        val first = assertNotNull(repo.getBook(id)).files
        db.bookFileDao().setProbed(first[0].id, 60_000, 1, 1)
        db.bookFileDao().setProbed(first[1].id, 40_000, 2, 1)
        db.chapterDao().insert(
            listOf(
                ChapterEntity(bookId = id, fileId = first[0].id, indexInBook = 0, title = "C1", startMs = 0, endMs = 10)
            )
        )

        repo.upsertDiscoveredBook(folderBook(audio("f1", "m1"), audio("f2", "m2")), acc, BookOrigin.SHARED, 99)

        val again = assertNotNull(repo.getBook(id))
        assertEquals(first.map { it.id }, again.files.map { it.id })
        assertEquals(listOf(60_000L, 40_000L), again.files.map { it.durationMs })
        assertEquals(100_000L, again.book.totalDurationMs)
        assertEquals(10L, again.book.addedAt)
        assertEquals(1, again.chapters.size)
    }

    @Test
    fun changedMd5ResetsProbeAndChaptersAndRemovedFileIsDeleted() = runTest {
        val id = repo.upsertDiscoveredBook(folderBook(audio("f1", "m1"), audio("f2", "m2")), acc, BookOrigin.SHARED, 10)
        val first = assertNotNull(repo.getBook(id)).files
        db.bookFileDao().setProbed(first[0].id, 60_000, 1, 1)
        db.chapterDao().insert(
            listOf(
                ChapterEntity(bookId = id, fileId = first[0].id, indexInBook = 0, title = "C1", startMs = 0, endMs = 10)
            )
        )

        repo.upsertDiscoveredBook(folderBook(audio("f1", "m1-new")), acc, BookOrigin.SHARED, 20)

        val again = assertNotNull(repo.getBook(id))
        assertEquals(listOf("f1"), again.files.map { it.driveFileId })
        assertEquals(first[0].id, again.files[0].id)
        assertNull(again.files[0].durationMs)
        assertEquals(false, again.files[0].probed)
        assertTrue(again.chapters.isEmpty())
    }

    @Test
    fun folderNeverOverwritesUserOrEmbeddedFields() = runTest {
        val id = repo.upsertDiscoveredBook(folderBook(audio("f1", "m1")), acc, BookOrigin.SHARED, 10)
        val book = assertNotNull(repo.getBook(id)).book
        val edited = book.copy(
            title = "Titre corrige",
            author = "Auteur ID3",
            fieldSources = book.fieldSources +
                (BookField.TITLE to FieldMeta(FieldSource.USER, 11)) +
                (BookField.AUTHOR to FieldMeta(FieldSource.EMBEDDED, 11))
        )
        db.bookDao().upsert(edited)

        repo.upsertDiscoveredBook(
            folderBook(
                audio("f1", "m1"),
                parsed = ParsedName(title = "Autre", author = "Autre auteur", seriesName = "S")
            ),
            acc,
            BookOrigin.SHARED,
            9999
        )

        val after = assertNotNull(repo.getBook(id)).book
        assertEquals("Titre corrige", after.title)
        assertEquals(FieldSource.USER, after.fieldSources[BookField.TITLE]?.source)
        assertEquals("Auteur ID3", after.author)
        assertEquals("S", after.seriesName) // empty field filled by FOLDER
    }

    @Test
    fun missingAndBackToPresent() = runTest {
        val a = repo.upsertDiscoveredBook(folderBook(audio("f1", "m1")), acc, BookOrigin.SHARED, 10)
        val other = DiscoveredBook(
            BookAnchor.Folder("folder2"),
            "root1",
            listOf(audio("g1", "n1")),
            emptyList(),
            ParsedName(title = "Autre"),
            emptyList()
        )
        val b = repo.upsertDiscoveredBook(other, acc, BookOrigin.SHARED, 10)
        val otherRoot =
            DiscoveredBook(
                BookAnchor.Folder("folder3"),
                "root2",
                listOf(audio("h1", "o1")),
                emptyList(),
                ParsedName(),
                emptyList()
            )
        val c = repo.upsertDiscoveredBook(otherRoot, acc, BookOrigin.SHARED, 10)

        assertEquals(1, repo.markMissingNotSeen("root1", setOf(a), 500))

        assertEquals(BookState.MISSING, db.bookDao().get(b)?.state)
        assertEquals(500L, db.bookDao().get(b)?.missingSince)
        assertEquals(BookState.PRESENT, db.bookDao().get(a)?.state)
        assertEquals(BookState.PRESENT, db.bookDao().get(c)?.state)
        assertEquals(setOf(a, c), repo.observeLibrary().first().map { it.book.bookId }.toSet())

        repo.upsertDiscoveredBook(other, acc, BookOrigin.SHARED, 600)
        assertEquals(BookState.PRESENT, db.bookDao().get(b)?.state)
        assertNull(db.bookDao().get(b)?.missingSince)
    }

    @Test
    fun purgeDeletesOldMissingBooksButNeverProgressOrBookmarks() = runTest {
        val id = repo.upsertDiscoveredBook(folderBook(audio("f1", "m1")), acc, BookOrigin.SHARED, 10)
        progress.savePosition(id, "f1", "m1", 0, 5_000, 5_000, ListeningStatus.IN_PROGRESS, 20)
        RoomBookmarkRepository(db, dispatcher).add(id, "m1", 1, 1, "note", 30)
        repo.markMissingNotSeen("root1", emptySet(), 100)

        assertEquals(0, repo.purgeMissingBefore(50)) // too recent
        assertEquals(1, repo.purgeMissingBefore(101))

        assertNull(db.bookDao().get(id))
        assertTrue(db.bookFileDao().getForBook(id).isEmpty())
        assertEquals(5_000L, progress.get(id)?.positionMs)
        assertEquals(1, RoomBookmarkRepository(db, dispatcher).observe(id).first().size)
    }

    @Test
    fun deletingBookDirectlyKeepsProgress() = runTest {
        val id = repo.upsertDiscoveredBook(folderBook(audio("f1", "m1")), acc, BookOrigin.SHARED, 10)
        progress.savePosition(id, "f1", "m1", 0, 7_000, 7_000, ListeningStatus.IN_PROGRESS, 20)
        db.bookDao().delete(listOf(id))
        assertEquals(7_000L, progress.get(id)?.positionMs)
    }
}
