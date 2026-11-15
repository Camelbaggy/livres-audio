package io.github.camelbaggy.livresaudio.core.library.scan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.camelbaggy.livresaudio.core.data.db.LibraryDatabase
import io.github.camelbaggy.livresaudio.core.data.identity.bookIdFor
import io.github.camelbaggy.livresaudio.core.data.repo.RoomAccountRepository
import io.github.camelbaggy.livresaudio.core.data.repo.RoomBookRootLookup
import io.github.camelbaggy.livresaudio.core.data.repo.RoomLibraryRepository
import io.github.camelbaggy.livresaudio.core.data.repo.RoomSyncStateRepository
import io.github.camelbaggy.livresaudio.core.data.repo.SyncStateRepository
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveError
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveResult
import io.github.camelbaggy.livresaudio.core.library.TestTree
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.core.model.BookAnchor
import io.github.camelbaggy.livresaudio.core.model.BookState
import io.github.camelbaggy.livresaudio.core.model.ConnectedAccount
import io.github.camelbaggy.livresaudio.core.model.DriveItem
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class ScriptedLister : TreeLister {
    var result: DriveResult<List<DriveItem>> = DriveResult.Failure(DriveError.NotFound("root"))
    var calls = 0

    override suspend fun list(accountId: AccountId, rootFolderId: String): DriveResult<List<DriveItem>> {
        calls++
        return result
    }
}

@RunWith(RobolectricTestRunner::class)
class IncrementalScanTest {
    private lateinit var db: LibraryDatabase
    private lateinit var accounts: RoomAccountRepository
    private lateinit var library: RoomLibraryRepository
    private lateinit var state: RoomSyncStateRepository
    private val lister = ScriptedLister()
    private val feed = FakeChangeFeed()
    private var now = 1_000_000_000L
    private lateinit var scanner: LibraryScanner
    private val tokenKey = SyncStateRepository.changesKey("shared")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        accounts = RoomAccountRepository(db, Dispatchers.Unconfined)
        library = RoomLibraryRepository(db, Dispatchers.Unconfined)
        state = RoomSyncStateRepository(db, Dispatchers.Unconfined)
        scanner = LibraryScanner(
            accounts,
            library,
            lister,
            feed,
            state,
            ChangedRootsResolver(feed, RoomBookRootLookup(db, Dispatchers.Unconfined)),
            Dispatchers.Unconfined,
            clock = { now }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun connect(root: String = "root") {
        accounts.upsertAccount(
            ConnectedAccount(AccountId("shared"), "s@example.org", null, AccountRole.SHARED, false),
            now
        )
        accounts.addWatchedRoot(AccountId("shared"), root, "racine $root", now)
    }

    /** Same ids for the shared prefix: "Livre Un" is always d2. */
    private fun tree(vararg books: String): TestTree {
        val t = TestTree()
        t.folder("Auteur Exemple") { a ->
            books.forEach { b -> t.folder(b, a) { f -> t.files(f, "01.mp3", "02.mp3") } }
        }
        return t
    }

    private fun TestTree.ok() = DriveResult.Success(items.toList())

    private fun batch(newToken: String, vararg entries: ChangeEntry) =
        DriveResult.Success(ChangeBatch(entries.toList(), newToken))

    private suspend fun stateOf(t: TestTree, title: String): BookState? =
        library.getBook(bookIdFor(BookAnchor.Folder(t.items.first { it.name == title }.id)))?.book?.state

    private suspend fun token() = state.get(tokenKey)?.token

    private suspend fun firstScan(vararg books: String): TestTree {
        val t = tree(*books)
        lister.result = t.ok()
        connect()
        scanner.scan()
        lister.calls = 0
        now += 1000
        return t
    }

    @Test
    fun firstScanListsEverythingAfterTakingTheToken() = runTest {
        val t = tree("Livre Un")
        lister.result = t.ok()
        connect()

        val report = scanner.scan()

        assertEquals(1, assertIs<RootScanOutcome.Done>(report.roots.single().outcome).added)
        assertEquals(listOf("start"), feed.events)
        assertEquals("t0", token())
        assertEquals(1, lister.calls)
    }

    @Test
    fun addedBookAppearsAndOnlyItsRootIsListed() = runTest {
        firstScan("Livre Un")
        val v2 = tree("Livre Un", "Livre Deux")
        lister.result = v2.ok()
        val newFolder = v2.items.first { it.name == "Livre Deux" }.id
        val author = v2.items.first { it.name == "Auteur Exemple" }.id
        feed.parents[author] = listOf("root")
        // The change is reported on a file inside the new folder: two levels below the root.
        feed.batches["t0"] = batch("t1", ChangeEntry("f-new", false, listOf(newFolder)))
        feed.parents[newFolder] = listOf(author)

        val report = scanner.scan()

        assertEquals(1, lister.calls)
        assertEquals(1, assertIs<RootScanOutcome.Done>(report.roots.single().outcome).added)
        assertEquals(BookState.PRESENT, stateOf(v2, "Livre Deux"))
        assertEquals("t1", token())
    }

    @Test
    fun removedBookBecomesMissingAndTrashedOneToo() = runTest {
        val full = firstScan("Livre Un", "Livre Deux")
        lister.result = tree("Livre Un").ok()
        val folder = full.items.first { it.name == "Livre Deux" }.id
        // Permanently deleted: no parents, found through the stored book.
        feed.batches["t0"] = batch("t1", ChangeEntry(folder, true, null))

        val report = scanner.scan()

        assertEquals(1, assertIs<RootScanOutcome.Done>(report.roots.single().outcome).missing)
        assertEquals(BookState.MISSING, stateOf(full, "Livre Deux"))
        assertEquals(BookState.PRESENT, stateOf(full, "Livre Un"))

        // Restored from the trash: the same book comes back (its progress was never touched).
        lister.result = full.ok()
        feed.batches["t1"] =
            batch("t2", ChangeEntry(folder, false, listOf(full.items.first { it.name == "Auteur Exemple" }.id)))
        feed.parents[full.items.first { it.name == "Auteur Exemple" }.id] = listOf("root")
        scanner.scan()
        assertEquals(BookState.PRESENT, stateOf(full, "Livre Deux"))
    }

    @Test
    fun trashedFileWithParentsIsResolvedThroughAncestors() = runTest {
        val full = firstScan("Livre Un", "Livre Deux")
        lister.result = tree("Livre Un").ok()
        val author = full.items.first { it.name == "Auteur Exemple" }.id
        feed.parents[author] = listOf("root")
        // Unknown id (not a stored anchor), parents given: climbing finds the root.
        feed.batches["t0"] = batch("t1", ChangeEntry("x", false, listOf(author)))

        scanner.scan()

        assertEquals(1, lister.calls)
        assertEquals(1, feed.parentCalls)
    }

    @Test
    fun changeOutsideWatchedRootsIsIgnored() = runTest {
        firstScan("Livre Un")
        feed.parents["elsewhere"] = listOf("myDriveRoot")
        feed.batches["t0"] = batch(
            "t1",
            ChangeEntry("a", false, listOf("elsewhere")),
            ChangeEntry("b", false, listOf("elsewhere")),
            ChangeEntry("c", true, null)
        )

        val report = scanner.scan()

        assertEquals(0, lister.calls)
        assertTrue(report.roots.isEmpty())
        assertEquals("t1", token())
        assertEquals(2, feed.parentCalls, "elsewhere and myDriveRoot, each asked once")
    }

    @Test
    fun tokenIsKeptWhenApplyingFailsThenChangesAreReplayed() = runTest {
        val full = firstScan("Livre Un", "Livre Deux")
        feed.parents["x"] = listOf("root")
        feed.batches["t0"] = batch("t1", ChangeEntry("y", false, listOf("root")))
        lister.result = DriveResult.Failure(DriveError.NetworkUnavailable)

        val failed = scanner.scan()

        assertIs<RootScanOutcome.Failed>(failed.roots.single().outcome)
        assertEquals("t0", token())
        assertEquals(BookState.PRESENT, stateOf(full, "Livre Deux"))

        lister.result = full.ok()
        scanner.scan()
        assertEquals("t1", token())
        assertEquals(listOf("start", "changes:t0", "changes:t0"), feed.events)
    }

    @Test
    fun networkFailureOnTheFeedChangesNothing() = runTest {
        val full = firstScan("Livre Un")
        feed.batches["t0"] = DriveResult.Failure(DriveError.NetworkUnavailable)

        val report = scanner.scan()

        assertEquals(0, lister.calls)
        assertEquals(
            ScanFailure.Drive(DriveError.NetworkUnavailable),
            (report.roots.single().outcome as RootScanOutcome.Failed).failure
        )
        assertEquals("t0", token())
        assertEquals(BookState.PRESENT, stateOf(full, "Livre Un"))
    }

    @Test
    fun failingAncestorLookupChangesNothing() = runTest {
        firstScan("Livre Un")
        feed.batches["t0"] = batch("t1", ChangeEntry("a", false, listOf("unknownFolder")))
        feed.parentsFailure = DriveError.NetworkUnavailable

        val report = scanner.scan()

        assertEquals(0, lister.calls)
        assertIs<RootScanOutcome.Failed>(report.roots.single().outcome)
        assertEquals("t0", token())
    }

    @Test
    fun expiredTokenTriggersFullWalkAndNewToken() = runTest {
        val t = tree("Livre Un")
        lister.result = t.ok()
        connect()
        state.put(tokenKey, "old", now)
        state.put(SyncStateRepository.fullScanKey("root"), null, now)
        // "old" has no batch: the fake answers InvalidChangeToken.

        val report = scanner.scan()

        assertEquals(1, lister.calls)
        assertEquals(1, assertIs<RootScanOutcome.Done>(report.roots.single().outcome).added)
        assertEquals("t0", token())
        assertEquals(listOf("changes:old", "start"), feed.events)
    }

    @Test
    fun weeklyControlWalkRunsEvenWithoutChanges() = runTest {
        firstScan("Livre Un")
        feed.batches["t0"] = batch("t1")
        now += TimeUnit.DAYS.toMillis(6)
        scanner.scan()
        assertEquals(0, lister.calls, "6 days: still incremental")

        feed.batches["t1"] = batch("t2")
        now += TimeUnit.DAYS.toMillis(2)
        scanner.scan()
        assertEquals(1, lister.calls, "8 days since the last complete listing")
        assertEquals("t2", token())

        feed.batches["t2"] = batch("t3")
        now += 1000
        scanner.scan()
        assertEquals(1, lister.calls, "the weekly walk restarted the clock")
    }

    @Test
    fun forcedFullModeListsEverythingAndRefreshesToken() = runTest {
        firstScan("Livre Un")

        scanner.scan(ScanMode.FULL)

        assertEquals(1, lister.calls)
        assertEquals(listOf("start", "start"), feed.events)
    }

    @Test
    fun rootAddedAfterTheTokenIsListedOnTheNextRun() = runTest {
        firstScan("Livre Un")
        accounts.addWatchedRoot(AccountId("shared"), "root2", "autre racine", now)
        feed.batches["t0"] = batch("t1")

        val report = scanner.scan()

        assertEquals(listOf("root2"), report.roots.map { it.driveFolderId })
    }

    @Test
    fun overflowOfAncestorLookupsFallsBackToAllRoots() = runTest {
        firstScan("Livre Un")
        val entries = (1..ChangedRootsResolver.MAX_LOOKUPS + 5).map { ChangeEntry("f$it", false, listOf("dir$it")) }
        feed.batches["t0"] = DriveResult.Success(ChangeBatch(entries, "t1"))

        val report = scanner.scan()

        assertEquals(1, lister.calls)
        assertIs<RootScanOutcome.Done>(report.roots.single().outcome)
        assertEquals("t1", token())
    }
}
