package io.github.camelbaggy.livresaudio.core.drive.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.core.model.ConnectedAccount
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** Connected accounts. Never stores a token (CLAUDE.md §5.1). Room replaces this at lot 1. */
interface AccountStore {
    val accounts: Flow<List<ConnectedAccount>>

    suspend fun list(): List<ConnectedAccount>

    suspend fun find(id: AccountId): ConnectedAccount?

    /** Adds the account, or updates role and name if the address is already known (same [AccountId]). */
    suspend fun upsert(email: String, displayName: String?, role: AccountRole): ConnectedAccount

    suspend fun remove(id: AccountId)
}

/**
 * The profile account (CLAUDE.md §4.1): the first PERSONAL account; with no PERSONAL account, the only
 * connected account. Pure.
 */
fun assignProfile(accounts: List<ConnectedAccount>): List<ConnectedAccount> {
    val profileId = accounts.firstOrNull { it.role == AccountRole.PERSONAL }?.id
        ?: accounts.singleOrNull()?.id
    return accounts.map { it.copy(isProfile = it.id == profileId) }
}

@Serializable
private data class StoredAccount(val id: String, val email: String, val displayName: String? = null, val role: String)

internal class DataStoreAccountStore(
    private val dataStore: DataStore<Preferences>,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) : AccountStore {
    private val key = stringPreferencesKey("accounts_json")
    private val serializer = ListSerializer(StoredAccount.serializer())

    override val accounts: Flow<List<ConnectedAccount>> = dataStore.data.map { decode(it[key]) }

    override suspend fun list(): List<ConnectedAccount> = accounts.first()

    override suspend fun find(id: AccountId): ConnectedAccount? = list().firstOrNull { it.id == id }

    override suspend fun upsert(email: String, displayName: String?, role: AccountRole): ConnectedAccount {
        var result: ConnectedAccount? = null
        dataStore.edit { prefs ->
            val current = decode(prefs[key])
            val existing = current.firstOrNull { it.email.equals(email, ignoreCase = true) }
            val updated = ConnectedAccount(
                id = existing?.id ?: AccountId(newId()),
                email = email,
                displayName = displayName ?: existing?.displayName,
                role = role,
                isProfile = false
            )
            val next = assignProfile(current.filter { it.id != updated.id } + updated)
            result = next.first { it.id == updated.id }
            prefs[key] = encode(next)
        }
        return requireNotNull(result)
    }

    override suspend fun remove(id: AccountId) {
        dataStore.edit { prefs ->
            prefs[key] = encode(assignProfile(decode(prefs[key]).filter { it.id != id }))
        }
    }

    private fun decode(raw: String?): List<ConnectedAccount> {
        if (raw.isNullOrBlank()) return emptyList()
        val stored = try {
            io.github.camelbaggy.livresaudio.core.drive.rest.DriveJson.decodeFromString(serializer, raw)
        } catch (_: Exception) {
            return emptyList()
        }
        return assignProfile(
            stored.mapNotNull { s ->
                val role = AccountRole.entries.firstOrNull { it.name == s.role } ?: return@mapNotNull null
                ConnectedAccount(AccountId(s.id), s.email, s.displayName, role, isProfile = false)
            }
        )
    }

    private fun encode(accounts: List<ConnectedAccount>): String =
        io.github.camelbaggy.livresaudio.core.drive.rest.DriveJson.encodeToString(
            serializer,
            accounts.map { StoredAccount(it.id.value, it.email, it.displayName, it.role.name) }
        )
}
