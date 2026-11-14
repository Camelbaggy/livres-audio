package io.github.camelbaggy.livresaudio.core.library.scan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.camelbaggy.livresaudio.core.data.db.LibraryDatabase
import io.github.camelbaggy.livresaudio.core.data.identity.bookIdFor
import io.github.camelbaggy.livresaudio.core.data.repo.RoomAccountRepository
import io.github.camelbaggy.livresaudio.core.data.repo.RoomLibraryRepository
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveError
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveResult
import io.github.camelbaggy.livresaudio.core.library.TestTree
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.core.model.BookAnchor
import io.github.camelbaggy.livresaudio.core.model.BookOrigin
import io.github.camelbaggy.livresaudio.core.model.BookState
import io.github.camelbaggy.livresaudio.core.model.ConnectedAccount
import io.github.camelbaggy.livresaudio.core.model.DriveItem
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class FakeLister : TreeLister {
    val results = mutableMapOf<String, DriveResult<List<DriveItem>>>()
    var calls = 0

    override suspend fun list(accountId: AccountId, rootFolderId: String): DriveResult<List<DriveItem>> {
        calls++
        return results[rootFolderId] ?: DriveResult.Failure(DriveError.NotFound("root"))
    }
}

@RunWith(RobolectricTestRunner::class)
class LibraryScannerTest {
    private lateinit var db: LibraryDatabase
    private lateinit var accounts: RoomAccountRepository
    private lateinit var library: RoomLibraryRepository
    private val lister = FakeLister()
    private var now = 1_000_000_000L
    private lateinit var scanner: LibraryScanner

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        accounts = RoomAccountRepository(db, Dispatchers.Unconfined)
        library = RoomLibraryRepository(db, Dispatchers.Unconfined)
        scanner = LibraryScanner(accounts, library, lister, Dispatchers.Unconfined, clock = { now })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun connect(id: String, role: AccountRole, root: String) {
        accounts.upsertAccount(ConnectedAccount(AccountId(id), "$id@example.org", null, role, false), now)
        accounts.addWatchedRoot(AccountId(id), root, "racine $root", now)
    }

    private fun seriesTree(vararg books: String): TestTree {
        val t = TestTree()
        t.folder("Auteur Exemple") { a ->
            books.forEach { b -> t.folder(b, a) { f -> t.files(f, "01.mp3", "02.mp3") } }
        }
        return t
    }

    /** A tree listed under another root id (TestTree always hangs its items on "root"). */
    private fun onRoot(t: TestTree, rootId: String) =
        DriveResult.Success(t.items.map { if (it.parentId == t.rootId) it.copy(parentId = rootId) else it })

    private fun TestTree.ok() = DriveResult.Success(items.toList())

    private suspend fun stateOf(tree: TestTree, title: String): BookState? {
        val folder = tree.items.first { it.name == title }.id
        return library.getBook(bookIdFor(BookAnchor.Folder(folder)))?.book?.state
    }

    @Test
    fun sharedRootUsesSeriesRoleAndSharedOrigin() = runTest {
        val t = TestTree()
        t.folder("Fortune Exemple") { s -> t.folder("Le Premier Pas", s) { f -> t.files(f, "01.mp3", "02.mp3") } }
        lister.results["root"] = t.ok()
        connect("shared", AccountRole.SHARED, "root")

        val report = scanner.scan()

        val done = assertIs<RootScanOutcome.Done>(report.roots.single().outcome)
        assertEquals(1, done.found)
        assertEquals(1, done.added)
        val book = assertNotNull(
            library.getBook(
                bookIdFor(
                    BookAnchor.Folder(
                        t.items.first {
                            it.name ==
                                "Le Premier Pas"
                        }.id
                    )
                )
            )
        ).book
        assertEquals(BookOrigin.SHARED, book.origin)
        assertEquals("Fortune Exemple", book.seriesName)
        assertNull(book.author)
    }

    @Test
    fun personalRootUsesAutoRoleAndPersonalOrigin() = runTest {
        val t = TestTree()
        t.folder("Fortune Exemple") { s -> t.folder("Le Premier Pas", s) { f -> t.files(f, "01.mp3", "02.mp3") } }
        lister.results["root"] = t.ok()
        connect("perso", AccountRole.PERSONAL, "root")

        scanner.scan()

        val book = assertNotNull(
            library.getBook(
                bookIdFor(
                    BookAnchor.Folder(
                        t.items.first {
                            it.name ==
                                "Le Premier Pas"
                        }.id
                    )
                )
            )
        ).book
        assertEquals(BookOrigin.PERSONAL, book.origin)
        assertEquals("Fortune Exemple", book.author)
        assertNull(book.seriesName)
    }

    @Test
    fun failedWalkNeverMarksBooksMissing() = runTest {
        val t = seriesTree("Livre Un", "Livre Deux")
        lister.results["root"] = t.ok()
        connect("shared", AccountRole.SHARED, "root")
        scanner.scan()

        lister.results["root"] = DriveResult.Failure(DriveError.NetworkUnavailable)
        val report = scanner.scan()

        val failed = assertIs<RootScanOutcome.Failed>(report.roots.single().outcome)
        assertEquals(ScanFailure.Drive(DriveError.NetworkUnavailable), failed.failure)
        assertEquals(BookState.PRESENT, stateOf(t, "Livre Un"))
        assertEquals(BookState.PRESENT, stateOf(t, "Livre Deux"))
    }

    @Test
    fun failureOnOneRootDoesNotAffectTheOther() = runTest {
        val a = seriesTree("Livre A")
        val b = TestTree()
        repeat(100) { b.folder("décalage ids") }
        b.items.clear() // ids must differ from tree A's (a book id derives from the Drive id)
        b.folder("Auteur B") { x -> b.folder("Livre B", x) { f -> b.files(f, "01.mp3") } }
        lister.results["root"] = a.ok()
        lister.results["rootB"] = onRoot(b, "rootB")
        connect("shared", AccountRole.SHARED, "root")
        connect("perso", AccountRole.PERSONAL, "rootB")
        scanner.scan()

        lister.results["root"] = DriveResult.Failure(DriveError.Server(503))
        val bEmpty = TestTree()
        bEmpty.folder("Auteur B")
        lister.results["rootB"] = onRoot(bEmpty, "rootB")
        val report = scanner.scan()

        assertIs<RootScanOutcome.Failed>(report.roots.first { it.driveFolderId == "root" }.outcome)
        assertEquals(
            1,
            assertIs<RootScanOutcome.Done>(
                report.roots.first {
                    it.driveFolderId == "rootB"
                }.outcome
            ).missing
        )
        assertEquals(BookState.PRESENT, stateOf(a, "Livre A"))
    }

    @Test
    fun missingBookThenReturnsPresent() = runTest {
        val full = seriesTree("Livre Un", "Livre Deux")
        lister.results["root"] = full.ok()
        connect("shared", AccountRole.SHARED, "root")
        scanner.scan()

        val withoutTwo = seriesTree("Livre Un")
        // Same folder ids as the full tree (generated in the same order): "Livre Un" keeps its id.
        lister.results["root"] = withoutTwo.ok()
        now += 1000
        val report = scanner.scan()

        assertEquals(1, assertIs<RootScanOutcome.Done>(report.roots.single().outcome).missing)
        assertEquals(BookState.PRESENT, stateOf(full, "Livre Un"))
        assertEquals(BookState.MISSING, stateOf(full, "Livre Deux"))

        lister.results["root"] = full.ok()
        now += 1000
        val back = scanner.scan()

        val done = assertIs<RootScanOutcome.Done>(back.roots.single().outcome)
        assertEquals(0, done.missing)
        assertEquals(0, done.added, "the returning book is not new")
        assertEquals(BookState.PRESENT, stateOf(full, "Livre Deux"))
    }

    @Test
    fun missingBooksArePurgedAfterNinetyDays() = runTest {
        val full = seriesTree("Livre Un", "Livre Deux")
        lister.results["root"] = full.ok()
        connect("shared", AccountRole.SHARED, "root")
        scanner.scan()
        lister.results["root"] = seriesTree("Livre Un").ok()
        now += 1000
        scanner.scan()

        now += TimeUnit.DAYS.toMillis(89)
        assertEquals(0, scanner.scan().purged)
        assertEquals(BookState.MISSING, stateOf(full, "Livre Deux"))

        now += TimeUnit.DAYS.toMillis(2)
        assertEquals(1, scanner.scan().purged)
        assertNull(stateOf(full, "Livre Deux"))
    }
}
