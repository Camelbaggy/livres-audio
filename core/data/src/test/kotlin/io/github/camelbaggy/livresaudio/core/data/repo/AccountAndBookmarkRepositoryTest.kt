package io.github.camelbaggy.livresaudio.core.data.repo

import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.core.model.ConnectedAccount
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
        accounts.removeAccount(AccountId("a"))
        assertTrue(accounts.getWatchedRoots().isEmpty())
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
