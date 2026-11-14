package io.github.camelbaggy.livresaudio.core.data.repo

import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.core.model.BookAnchor
import io.github.camelbaggy.livresaudio.core.model.BookOrigin
import io.github.camelbaggy.livresaudio.core.model.BookState
import io.github.camelbaggy.livresaudio.core.model.ConnectedAccount
import io.github.camelbaggy.livresaudio.core.model.DiscoveredBook
import io.github.camelbaggy.livresaudio.core.model.DriveItem
import io.github.camelbaggy.livresaudio.core.model.ListeningStatus
import io.github.camelbaggy.livresaudio.core.model.ParsedName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class AccountAndBookmarkRepositoryTest : RepoTestBase() {
    private val accounts by lazy { RoomAccountRepository(db, dispatcher) }
    private val bookmarks by lazy { RoomBookmarkRepository(db, dispatcher) }

    private fun account(id: String, role: AccountRole, profile: Boolean) =
        ConnectedAccount(AccountId(id), "$id@example.test", null, role, profile)

    @Test
    fun onlyOneProfileAndAddedAtKept() = runTest {
        accounts.upsertAccount(account("a", AccountRole.PERSONAL, true), 10)
        accounts.upsertAccount(account("s", AccountRole.SHARED, false), 20)
        accounts.upsertAccount(account("b", AccountRole.PERSONAL, true), 30)
        accounts.upsertAccount(account("a", AccountRole.PERSONAL, false), 99)

        val all = accounts.observeAccounts().first()
        assertEquals(listOf("b"), all.filter { it.isProfile }.map { it.id.value })
        assertEquals(10L, db.accountDao().get("a")?.addedAt)
    }

    @Test
    fun rootsAreAddedOnceAndRemovedWithAccountButBooksAreNot() = runTest {
        accounts.upsertAccount(account("a", AccountRole.SHARED, false), 10)
        accounts.addWatchedRoot(AccountId("a"), "folderX", "Livres", 11)
        accounts.addWatchedRoot(AccountId("a"), "folderX", "Livres", 12)
        assertEquals(1, accounts.getWatchedRoots().size)
        accounts.removeAccount(AccountId("a"), 50)
        assertTrue(accounts.getWatchedRoots().isEmpty())
    }

    private suspend fun book(id: String, accountId: String, root: String): String {
        val discovered = DiscoveredBook(
            BookAnchor.Folder(id),
            root,
            listOf(DriveItem("f-$id", "$id.mp3", "audio/mpeg", id, 10, "m-$id", 1L)),
            emptyList(),
            ParsedName(title = id),
            emptyList()
        )
        return RoomLibraryRepository(db, dispatcher)
            .upsertDiscoveredBook(discovered, AccountId(accountId), BookOrigin.PERSONAL, 1)
    }

    @Test
    fun removingAccountFlagsItsBooksMissingButKeepsBooksProgressAndBookmarks() = runTest {
        accounts.upsertAccount(account("a", AccountRole.PERSONAL, true), 10)
        accounts.upsertAccount(account("s", AccountRole.SHARED, false), 10)
        accounts.addWatchedRoot(AccountId("a"), "rootA", "Perso", 11)
        val mine = book("livreA", "a", "rootA")
        val shared = book("livreS", "s", "rootS")
        RoomProgressRepository(db, dispatcher)
            .savePosition(mine, "f-livreA", "m-livreA", 0, 9_000, 9_000, ListeningStatus.IN_PROGRESS, 20)
        bookmarks.add(mine, "m-livreA", 1, 1, "note", 30)

        accounts.removeAccount(AccountId("a"), 777)

        assertEquals(BookState.MISSING, db.bookDao().get(mine)?.state)
        assertEquals(777L, db.bookDao().get(mine)?.missingSince)
        assertEquals(BookState.PRESENT, db.bookDao().get(shared)?.state)
        assertTrue(accounts.getWatchedRoots().isEmpty())
        assertEquals(9_000L, db.progressDao().get(mine)?.positionMs)
        assertEquals(1, bookmarks.observe(mine).first().size)
    }

    @Test
    fun removingARootFlagsOnlyItsBooksMissing() = runTest {
        accounts.upsertAccount(account("a", AccountRole.PERSONAL, true), 10)
        accounts.addWatchedRoot(AccountId("a"), "rootA", "A", 11)
        accounts.addWatchedRoot(AccountId("a"), "rootB", "B", 12)
        val inA = book("livreA", "a", "rootA")
        val inB = book("livreB", "a", "rootB")
        val rootA = accounts.getWatchedRoots().first { it.driveFolderId == "rootA" }

        accounts.removeWatchedRoot(rootA.id, 400)

        assertEquals(BookState.MISSING, db.bookDao().get(inA)?.state)
        assertEquals(BookState.PRESENT, db.bookDao().get(inB)?.state)
        assertEquals(listOf("rootB"), accounts.getWatchedRoots().map { it.driveFolderId })
    }

    @Test
    fun bookmarkLifecycleWithTombstone() = runTest {
        val id = bookmarks.add("b1", "m1", 10, 20, null, 100, id = "bm-1")
        bookmarks.add("b1", "m1", 5, 5, "debut", 101, id = "bm-2")
        assertEquals(listOf("bm-2", "bm-1"), bookmarks.observe("b1").first().map { it.id })

        bookmarks.updateNote(id, "texte", 200)
        assertEquals("texte", db.bookmarkDao().get(id)?.note)

        bookmarks.delete(id, 300)
        assertEquals(listOf("bm-2"), bookmarks.observe("b1").first().map { it.id })
        val tomb = db.bookmarkDao().get(id)
        assertEquals(true, tomb?.deleted)
        assertEquals(true, tomb?.dirty)
        assertEquals(300L, tomb?.updatedAt)
    }
}
