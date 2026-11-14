package io.github.camelbaggy.livresaudio.core.drive.rest

import io.github.camelbaggy.livresaudio.core.model.AccessToken
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.core.model.AuthResult
import io.github.camelbaggy.livresaudio.core.model.AuthUi
import io.github.camelbaggy.livresaudio.core.model.ConnectedAccount
import io.github.camelbaggy.livresaudio.core.model.DriveAuthProvider
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

private class FakeAuth(private var next: AuthResult<AccessToken>? = null) : DriveAuthProvider {
    val calls = mutableListOf<Boolean>()
    var current = "tok-1"
    var afterRefresh = "tok-2"

    override suspend fun addAccount(ui: AuthUi, role: AccountRole): AuthResult<ConnectedAccount> = AuthResult.Cancelled

    override suspend fun removeAccount(accountId: AccountId) = Unit

    override suspend fun getAccessToken(accountId: AccountId, forceRefresh: Boolean): AuthResult<AccessToken> {
        calls += forceRefresh
        next?.let { return it }
        if (forceRefresh) current = afterRefresh
        return AuthResult.Success(AccessToken(current, null))
    }
}

class DriveRestClientTest {
    private lateinit var server: MockWebServer
    private val account = AccountId("acc-1")
    private val sleeps = mutableListOf<Long>()
    private lateinit var auth: FakeAuth
    private lateinit var client: DriveRestClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        auth = FakeAuth()
        client = buildClient(auth)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun buildClient(provider: DriveAuthProvider) = DriveHttp.createClient(
        auth = { provider },
        baseUrl = server.url("/drive/v3/"),
        retry = RetryInterceptor(maxAttempts = 3, baseDelayMs = 100, sleeper = { sleeps += it })
    )

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun paginatedListFollowsNextPageToken() = runTest {
        server.enqueue(json("""{"nextPageToken":"p2","files":[{"id":"1","name":"a"}]}"""))
        server.enqueue(json("""{"files":[{"id":"2","name":"b"}],"unknownField":1}"""))

        val result = client.listAllFiles(account, "'root' in parents and trashed=false")

        val files = assertIs<DriveResult.Success<List<DriveFile>>>(result).value
        assertEquals(listOf("1", "2"), files.map { it.id })
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("Bearer tok-1", first.getHeader("Authorization"))
        assertEquals("'root' in parents and trashed=false", first.requestUrl?.queryParameter("q"))
        assertTrue(first.requestUrl?.queryParameter("fields")?.contains("files(") == true)
        assertEquals("p2", second.requestUrl?.queryParameter("pageToken"))
    }

    @Test
    fun appDataSpaceIsPassedToList() = runTest {
        server.enqueue(json("""{"files":[]}"""))
        client.listFiles(account, "name='x'", spaces = APP_DATA_FOLDER)
        assertEquals("appDataFolder", server.takeRequest().requestUrl?.queryParameter("spaces"))
    }

    @Test
    fun unauthorizedRenewsTokenOnceThenSucceeds() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(json("""{"files":[{"id":"1"}]}"""))

        val result = client.listFiles(account)

        assertIs<DriveResult.Success<DriveFileList>>(result)
        assertEquals("Bearer tok-1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer tok-2", server.takeRequest().getHeader("Authorization"))
        assertEquals(listOf(false, true), auth.calls)
    }

    @Test
    fun unauthorizedTwiceGivesAuthRequiredWithoutLooping() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.listFiles(account)

        assertEquals(DriveResult.Failure(DriveError.AuthRequired), result)
        assertEquals(2, server.requestCount)
        assertEquals(listOf(false, true), auth.calls)
    }

    @Test
    fun quotaForbiddenIsRetriedWithBackoff() = runTest {
        val quota = """{"error":{"code":403,"message":"Rate Limit","errors":[{"reason":"userRateLimitExceeded"}]}}"""
        server.enqueue(json(quota, 403))
        server.enqueue(json(quota, 403))
        server.enqueue(json("""{"files":[]}"""))

        val result = client.listFiles(account)

        assertIs<DriveResult.Success<DriveFileList>>(result)
        assertEquals(3, server.requestCount)
        assertEquals(listOf(100L, 200L), sleeps)
    }

    @Test
    fun quotaStillExceededAfterThreeAttemptsIsRateLimited() = runTest {
        val quota = """{"error":{"code":403,"message":"Rate Limit","errors":[{"reason":"rateLimitExceeded"}]}}"""
        repeat(3) { server.enqueue(json(quota, 403)) }

        val result = client.listFiles(account)

        val error = assertIs<DriveResult.Failure>(result).error
        assertIs<DriveError.RateLimited>(error)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun otherForbiddenIsNotRetried() = runTest {
        server.enqueue(
            json("""{"error":{"code":403,"message":"nope","errors":[{"reason":"insufficientFilePermissions"}]}}""", 403)
        )

        val error = assertIs<DriveResult.Failure>(client.updateFileContent(account, "f1", "{}")).error

        assertEquals(DriveError.Forbidden("insufficientFilePermissions", "nope"), error)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun serverErrorsAreRetriedThreeTimesThenTyped() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }

        val result = client.getFile(account, "f1")

        assertEquals(DriveResult.Failure(DriveError.Server(503)), result)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun notFoundIsTyped() = runTest {
        server.enqueue(json("""{"error":{"code":404,"message":"File not found: x."}}""", 404))

        val error = assertIs<DriveResult.Failure>(client.getFile(account, "x")).error

        assertEquals(DriveError.NotFound("File not found: x."), error)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun multipartCreationIsWellFormed() = runTest {
        server.enqueue(json("""{"id":"new1","name":"essai-e1.json"}"""))

        val result = client.createJsonFile(account, "essai-e1.json", APP_DATA_FOLDER, """{"a":1}""")

        assertEquals("new1", assertIs<DriveResult.Success<DriveFile>>(result).value.id)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/upload/drive/v3/files", request.requestUrl?.encodedPath)
        assertEquals("multipart", request.requestUrl?.queryParameter("uploadType"))
        val contentType = requireNotNull(request.getHeader("Content-Type"))
        assertTrue(contentType.startsWith("multipart/related; boundary="), contentType)
        val boundary = contentType.substringAfter("boundary=")
        val body = request.body.readUtf8()
        val parts = body.split("--$boundary").filter { it.isNotBlank() && !it.trim().startsWith("--") }
        assertEquals(2, parts.size)
        assertTrue(parts[0].contains("Content-Type: application/json; charset=utf-8"))
        assertTrue(parts[0].contains(""""name":"essai-e1.json""""))
        assertTrue(parts[0].contains(""""parents":["appDataFolder"]"""))
        assertTrue(parts[1].contains("""{"a":1}"""))
        assertTrue(body.trimEnd().endsWith("--$boundary--"))
    }

    @Test
    fun updateUsesPatchWithMediaUpload() = runTest {
        server.enqueue(json("""{"id":"f1"}"""))

        client.updateFileContent(account, "f1", """{"b":2}""")

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/upload/drive/v3/files/f1", request.requestUrl?.encodedPath)
        assertEquals("media", request.requestUrl?.queryParameter("uploadType"))
        assertEquals("""{"b":2}""", request.body.readUtf8())
    }

    @Test
    fun createFolderSendsFolderMimeType() = runTest {
        server.enqueue(json("""{"id":"d1"}"""))

        client.createFolder(account, ".livres-audio-essai", parentId = null)

        val request = server.takeRequest()
        assertEquals("/drive/v3/files", request.requestUrl?.encodedPath)
        assertEquals("""{"name":".livres-audio-essai","mimeType":"$FOLDER_MIME_TYPE"}""", request.body.readUtf8())
    }

    @Test
    fun downloadReadsRawContent() = runTest {
        server.enqueue(MockResponse().setBody("""{"hello":"monde"}"""))

        val result = client.downloadText(account, "f1")

        assertEquals("""{"hello":"monde"}""", assertIs<DriveResult.Success<String>>(result).value)
        assertEquals("media", server.takeRequest().requestUrl?.queryParameter("alt"))
    }

    @Test
    fun aboutWithTokenUsesExplicitHeaderAndParsesUser() = runTest {
        server.enqueue(json("""{"user":{"emailAddress":"a@example.test","displayName":"A"}}"""))

        val result = client.aboutWithToken(AccessToken("explicit", null))

        assertEquals("a@example.test", assertIs<DriveResult.Success<DriveUser>>(result).value.emailAddress)
        val request = server.takeRequest()
        assertEquals("Bearer explicit", request.getHeader("Authorization"))
        assertEquals("user(emailAddress,displayName)", request.requestUrl?.queryParameter("fields"))
        assertTrue(auth.calls.isEmpty())
    }

    @Test
    fun tokenNeedingUserActionGivesAuthRequired() = runTest {
        val noToken = FakeAuth(AuthResult.NeedsUserAction)
        val result = buildClient(noToken).listFiles(account)
        assertEquals(DriveResult.Failure(DriveError.AuthRequired), result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun noNetworkGivesNetworkUnavailable() = runTest {
        server.shutdown()
        assertEquals(DriveResult.Failure(DriveError.NetworkUnavailable), client.listFiles(account))
    }

    @Test
    fun noNetworkDuringTokenGivesNetworkUnavailable() = runTest {
        val offline = FakeAuth(AuthResult.NetworkUnavailable)
        assertEquals(DriveResult.Failure(DriveError.NetworkUnavailable), buildClient(offline).listFiles(account))
    }
}
