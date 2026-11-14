package io.github.camelbaggy.livresaudio.core.playback

import androidx.media3.common.util.UnstableApi
import io.github.camelbaggy.livresaudio.core.drive.rest.AuthFailedException
import io.github.camelbaggy.livresaudio.core.model.AccessToken
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.core.model.AuthResult
import io.github.camelbaggy.livresaudio.core.model.AuthUi
import io.github.camelbaggy.livresaudio.core.model.ConnectedAccount
import io.github.camelbaggy.livresaudio.core.model.DriveAuthProvider
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

private class FakeAuth(private val outcome: AuthResult<AccessToken>? = null) : DriveAuthProvider {
    val calls = mutableListOf<Pair<String, Boolean>>()

    override suspend fun addAccount(ui: AuthUi, role: AccountRole): AuthResult<ConnectedAccount> = AuthResult.Cancelled

    override suspend fun removeAccount(accountId: AccountId) = Unit

    override suspend fun getAccessToken(accountId: AccountId, forceRefresh: Boolean): AuthResult<AccessToken> {
        calls += accountId.value to forceRefresh
        return outcome
            ?: AuthResult.Success(
                AccessToken(if (forceRefresh) "new-${accountId.value}" else "tok-${accountId.value}", null)
            )
    }
}

/**
 * The OkHttp half of the player's data source chain, against a MockWebServer. (The Media3 half needs
 * `android.net.Uri`, hence Robolectric, whose native runtime cannot be downloaded in the cloud environment.)
 */
@UnstableApi
class DriveHttpChainTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun request(account: String = "acc-1") = Request.Builder()
        .url(server.url("/drive/v3/files/FILE1?alt=media"))
        .header("Range", "bytes=100-199")
        .header(ACCOUNT_HEADER, account)
        .build()

    @Test
    fun rangeRequestGetsTheAccountsBearerAndTheInternalHeaderIsStripped() {
        server.enqueue(MockResponse().setResponseCode(206).setBody("x".repeat(100)))
        val auth = FakeAuth()
        val client = DriveDataSourceFactory.createHttpClient({ auth })

        client.newCall(request()).execute().use { assertEquals(206, it.code) }

        val recorded = server.takeRequest()
        assertEquals("/drive/v3/files/FILE1?alt=media", recorded.path)
        assertEquals("bytes=100-199", recorded.getHeader("Range"))
        assertEquals("Bearer tok-acc-1", recorded.getHeader("Authorization"))
        assertNull(recorded.getHeader(ACCOUNT_HEADER), "the internal header must never leave the device")
        assertEquals(listOf("acc-1" to false), auth.calls)
    }

    @Test
    fun eachRequestUsesItsOwnAccount() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(206)) }
        val client = DriveDataSourceFactory.createHttpClient({ FakeAuth() })

        client.newCall(request("acc-1")).execute().close()
        client.newCall(request("acc-2")).execute().close()

        assertEquals("Bearer tok-acc-1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer tok-acc-2", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun expiredTokenIsRenewedOnceOn401() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(206))
        val auth = FakeAuth()
        val client = DriveDataSourceFactory.createHttpClient({ auth })

        client.newCall(request()).execute().use { assertEquals(206, it.code) }

        assertEquals("Bearer tok-acc-1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer new-acc-1", server.takeRequest().getHeader("Authorization"))
        assertEquals(listOf("acc-1" to false, "acc-1" to true), auth.calls)
    }

    @Test
    fun missingTokenSurfacesAsAuthFailedAndIsClassified() {
        val client = DriveDataSourceFactory.createHttpClient({ FakeAuth(AuthResult.NetworkUnavailable) })
        val error = runCatching { client.newCall(request()).execute() }.exceptionOrNull()
        assertIs<AuthFailedException>(error)
        assertEquals(LoadFailure.Network, DriveLoadErrorHandlingPolicy.classify(error))
        assertEquals(0, server.requestCount)
    }
}
