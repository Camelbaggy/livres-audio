package io.github.camelbaggy.livresaudio.core.drive.auth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.core.model.ConnectedAccount
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest

class AccountStoreTest {
    private fun acc(id: String, role: AccountRole) =
        ConnectedAccount(AccountId(id), "$id@example.test", null, role, false)

    @Test
    fun profileIsFirstPersonalOrTheOnlyAccount() {
        assertEquals(emptyList(), assignProfile(emptyList()))
        assertEquals(listOf(true), assignProfile(listOf(acc("s", AccountRole.SHARED))).map { it.isProfile })
        assertEquals(
            listOf(false, true),
            assignProfile(listOf(acc("s", AccountRole.SHARED), acc("p", AccountRole.PERSONAL))).map { it.isProfile }
        )
        assertEquals(
            listOf(false, false),
            assignProfile(listOf(acc("s1", AccountRole.SHARED), acc("s2", AccountRole.SHARED))).map { it.isProfile }
        )
        assertEquals(
            listOf(true, false),
            assignProfile(listOf(acc("p1", AccountRole.PERSONAL), acc("p2", AccountRole.PERSONAL))).map { it.isProfile }
        )
    }

    private fun store(file: File, scope: CoroutineScope) = DataStoreAccountStore(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
        newId = run {
            var n = 0
            { "id-${++n}" }
        }
    )

    @Test
    fun upsertKeepsIdForSameAddressAndRemoveWorks() = runTest {
        val file = File.createTempFile("accounts", ".preferences_pb").also {
            it.delete()
            it.deleteOnExit()
        }
        val scope = CoroutineScope(Job())
        val store = store(file, scope)

        val shared = store.upsert("Commun@Example.test", "Famille", AccountRole.SHARED)
        assertTrue(shared.isProfile)
        val personal = store.upsert("moi@example.test", null, AccountRole.PERSONAL)
        assertTrue(personal.isProfile)
        assertFalse(store.find(shared.id)!!.isProfile)

        val again = store.upsert("commun@example.test", null, AccountRole.SHARED)
        assertEquals(shared.id, again.id)
        assertEquals("Famille", again.displayName)
        assertEquals(2, store.list().size)

        store.remove(personal.id)
        assertNull(store.find(personal.id))
        assertTrue(store.find(shared.id)!!.isProfile)
        scope.cancel()
    }

    @Test
    fun persistedFileContainsNoToken() = runTest {
        val file = File.createTempFile("accounts", ".preferences_pb").also {
            it.delete()
            it.deleteOnExit()
        }
        val first = CoroutineScope(Job())
        store(file, first).upsert("moi@example.test", "Moi", AccountRole.PERSONAL)
        first.cancel()

        val second = CoroutineScope(Job())
        val reread = store(file, second).list()
        second.cancel()
        assertEquals(listOf("moi@example.test"), reread.map { it.email })
        // The schema has no token field at all: only id, email, name and role are persisted.
        assertFalse(file.readText(Charsets.ISO_8859_1).contains("token", ignoreCase = true))
    }
}
