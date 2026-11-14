package io.github.camelbaggy.livresaudio.core.drive.auth

import android.app.PendingIntent
import android.content.Intent
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveError
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveResult
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveUser
import io.github.camelbaggy.livresaudio.core.model.AccessToken
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.core.model.AuthResult
import io.github.camelbaggy.livresaudio.core.model.AuthUi
import io.github.camelbaggy.livresaudio.core.model.ConnectedAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

private class MemoryStore : AccountStore {
    private val state = MutableStateFlow<List<ConnectedAccount>>(emptyList())
    private var n = 0
    override val accounts: Flow<List<ConnectedAccount>> = state

    override suspend fun list() = state.value

    override suspend fun find(id: AccountId) = state.value.firstOrNull { it.id == id }

    override suspend fun upsert(email: String, displayName: String?, role: AccountRole): ConnectedAccount {
        val existing = state.value.firstOrNull { it.email == email }
        val account = ConnectedAccount(existing?.id ?: AccountId("id-${++n}"), email, displayName, role, true)
        state.value = state.value.filter { it.id != account.id } + account
        return account
    }

    override suspend fun remove(id: AccountId) {
        state.value = state.value.filter { it.id != id }
    }
}

private class FakeGateway(var result: GatewayResult) : AuthorizationGateway {
    val authorized = mutableListOf<Pair<String, Boolean>>()
    val cleared = mutableListOf<String>()

    override suspend fun authorize(email: String, ui: ActivityAuthUi?): GatewayResult {
        authorized += email to (ui != null)
        return result
    }

    override suspend fun clearToken(token: String) {
        cleared += token
    }
}

private class FakeUi(private val chosen: String?) : ActivityAuthUi {
    override suspend fun chooseGoogleAccount() = chosen

    override suspend fun launchConsent(pendingIntent: PendingIntent): Intent? = null
}

class GoogleDriveAuthProviderTest {
    private val store = MemoryStore()
    private val gateway = FakeGateway(GatewayResult.Granted(AccessToken("t1", null)))
    private var about: DriveResult<DriveUser> = DriveResult.Success(DriveUser("Nom", "Réel@Example.test"))
    private val provider = GoogleDriveAuthProvider(store, gateway) { about }

    @Test
    fun addAccountStoresAddressReturnedByAbout() = runTest {
        val result = provider.addAccount(FakeUi("choisi@example.test"), AccountRole.PERSONAL)

        val account = assertIs<AuthResult.Success<ConnectedAccount>>(result).value
        assertEquals("Réel@Example.test", account.email)
        assertEquals(AccountRole.PERSONAL, account.role)
        assertEquals(listOf("choisi@example.test" to true), gateway.authorized)
        assertEquals(1, store.list().size)
    }

    @Test
    fun cancelledChooserOrConsentStoresNothing() = runTest {
        assertEquals(AuthResult.Cancelled, provider.addAccount(FakeUi(null), AccountRole.SHARED))
        gateway.result = GatewayResult.Cancelled
        assertEquals(AuthResult.Cancelled, provider.addAccount(FakeUi("x@example.test"), AccountRole.SHARED))
        assertEquals(0, store.list().size)
    }

    @Test
    fun aboutFailureIsMappedAndNothingStored() = runTest {
        about = DriveResult.Failure(DriveError.NetworkUnavailable)
        assertEquals(AuthResult.NetworkUnavailable, provider.addAccount(FakeUi("x@example.test"), AccountRole.SHARED))
        assertEquals(0, store.list().size)
    }

    @Test
    fun incompatibleUiIsRefused() = runTest {
        val result = provider.addAccount(object : AuthUi {}, AccountRole.SHARED)
        assertIs<AuthResult.Failure>(result)
    }

    @Test
    fun silentTokenTargetsStoredAddressWithoutUi() = runTest {
        val account = store.upsert("moi@example.test", null, AccountRole.PERSONAL)

        val result = provider.getAccessToken(account.id)

        assertEquals("t1", assertIs<AuthResult.Success<AccessToken>>(result).value.value)
        assertEquals(listOf("moi@example.test" to false), gateway.authorized)
    }

    @Test
    fun forceRefreshInvalidatesPreviousToken() = runTest {
        val account = store.upsert("moi@example.test", null, AccountRole.PERSONAL)
        provider.getAccessToken(account.id)
        gateway.result = GatewayResult.Granted(AccessToken("t2", null))

        val result = provider.getAccessToken(account.id, forceRefresh = true)

        assertEquals(listOf("t1"), gateway.cleared)
        assertEquals("t2", assertIs<AuthResult.Success<AccessToken>>(result).value.value)
    }

    @Test
    fun consentToRedoNeedsUserActionAndOfflineIsNotAnAuthFailure() = runTest {
        val account = store.upsert("moi@example.test", null, AccountRole.PERSONAL)
        gateway.result = GatewayResult.ConsentRequired
        assertEquals(AuthResult.NeedsUserAction, provider.getAccessToken(account.id))
        gateway.result = GatewayResult.NetworkUnavailable
        assertEquals(AuthResult.NetworkUnavailable, provider.getAccessToken(account.id))
    }

    @Test
    fun unknownAccountFails() = runTest {
        assertIs<AuthResult.Failure>(provider.getAccessToken(AccountId("absent")))
    }

    @Test
    fun removeAccountClearsTokenAndStore() = runTest {
        val account = store.upsert("moi@example.test", null, AccountRole.PERSONAL)
        provider.getAccessToken(account.id)

        provider.removeAccount(account.id)

        assertEquals(listOf("t1"), gateway.cleared)
        assertEquals(0, store.list().size)
    }
}
