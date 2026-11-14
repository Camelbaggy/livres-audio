package io.github.camelbaggy.livresaudio.core.library.scan

import io.github.camelbaggy.livresaudio.core.drive.rest.DriveError
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveHttp
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveResult
import io.github.camelbaggy.livresaudio.core.drive.rest.RetryInterceptor
import io.github.camelbaggy.livresaudio.core.model.AccessToken
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.core.model.AuthResult
import io.github.camelbaggy.livresaudio.core.model.AuthUi
import io.github.camelbaggy.livresaudio.core.model.ConnectedAccount
import io.github.camelbaggy.livresaudio.core.model.DriveAuthProvider
import io.github.camelbaggy.livresaudio.core.model.DriveItem
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

private class StaticAuth : DriveAuthProvider {
    override suspend fun addAccount(ui: AuthUi, role: AccountRole): AuthResult<ConnectedAccount> = AuthResult.Cancelled

    override suspend fun removeAccount(accountId: AccountId) = Unit

    override suspend fun getAccessToken(accountId: AccountId, forceRefresh: Boolean): AuthResult<AccessToken> =
        AuthResult.Success(AccessToken("tok", null))
}

class DriveTreeListerTest {
    private lateinit var server: MockWebServer
    private val account = AccountId("acc")
    private val folder = "application/vnd.google-apps.folder"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun lister(batchSize: Int = 20, maxPages: Int = 200): DriveTreeLister {
        val client = DriveHttp.createClient(
            auth = { StaticAuth() },
            baseUrl = server.url("/drive/v3/"),
            retry = RetryInterceptor(maxAttempts = 1, baseDelayMs = 1, sleeper = { })
        )
        return DriveTreeLister(client, batchSize = batchSize, maxPagesPerQuery = maxPages)
    }

    private fun fileJson(id: String, name: String, parent: String, mime: String = "audio/mpeg", extra: String = "") =
        """{"id":"$id","name":"$name","mimeType":"$mime","parents":["$parent"]$extra}"""

    private fun page(vararg files: String, next: String? = null) =
        MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{${next?.let { "\"nextPageToken\":\"$it\"," } ?: ""}"files":[${files.joinToString(",")}]}""")

    @Test
    fun walksSeveralLevelsBatchesParentsAndConvertsItems() = runTest {
        val queries = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val q = request.requestUrl!!.queryParameter("q")!!
                queries += q
                return when {
                    "'root' in parents" in q -> page(
                        fileJson("A", "Auteur", "root", folder),
                        fileJson("B", "Autre", "root", folder),
                        fileJson(
                            "r1",
                            "solo.mp3",
                            "root",
                            extra = ""","size":"42","md5Checksum":"m1","modifiedTime":"2026-01-02T03:04:05.000Z""""
                        )
                    )

                    "'A' in parents" in q && "'B' in parents" in q -> page(
                        fileJson("A1", "Livre", "A", folder),
                        fileJson("b1", "b.mp3", "B")
                    )

                    "'A1' in parents" in q -> page(fileJson("a1", "01.mp3", "A1"))

                    else -> MockResponse().setResponseCode(500)
                }
            }
        }

        val result = lister().list(account, "root")

        val items = assertIs<DriveResult.Success<List<DriveItem>>>(result).value
        assertEquals(setOf("A", "B", "r1", "A1", "b1", "a1"), items.map { it.id }.toSet())
        assertEquals(3, queries.size, "root, then A+B together, then A1")
        assertEquals("A", items.single { it.id == "A1" }.parentId)
        val solo = items.single { it.id == "r1" }
        assertEquals(42L, solo.sizeBytes)
        assertEquals("m1", solo.md5)
        assertEquals(1767323045000L, solo.modifiedTimeMs)
        assertTrue(items.single { it.id == "A" }.isFolder)
        assertTrue(queries.all { it.endsWith("and trashed=false") })
    }

    @Test
    fun followsPaginationWithinAQuery() = runTest {
        server.enqueue(page(fileJson("1", "a.mp3", "root"), next = "p2"))
        server.enqueue(page(fileJson("2", "b.mp3", "root")))

        val items = assertIs<DriveResult.Success<List<DriveItem>>>(lister().list(account, "root")).value

        assertEquals(listOf("1", "2"), items.map { it.id })
        server.takeRequest()
        assertEquals("p2", server.takeRequest().requestUrl?.queryParameter("pageToken"))
    }

    @Test
    fun splitsParentsInBatchesOfAtMostBatchSize() = runTest {
        val folders = (1..5).map { fileJson("F$it", "d$it", "root", folder) }
        server.enqueue(page(*folders.toTypedArray()))
        repeat(3) { server.enqueue(page()) }

        val result = lister(batchSize = 2).list(account, "root")

        assertIs<DriveResult.Success<List<DriveItem>>>(result)
        assertEquals(4, server.requestCount, "root + ceil(5/2) batches")
    }

    @Test
    fun errorInTheMiddleFailsTheWholeListing() = runTest {
        server.enqueue(page(fileJson("A", "Auteur", "root", folder), fileJson("x", "x.mp3", "root")))
        server.enqueue(MockResponse().setResponseCode(500))

        val result = lister().list(account, "root")

        val failure = assertIs<DriveResult.Failure>(result)
        assertEquals(DriveError.Server(500), failure.error)
    }

    @Test
    fun tooManyPagesIsAFailureNotATruncatedSuccess() = runTest {
        server.enqueue(page(fileJson("1", "a.mp3", "root"), next = "p2"))
        server.enqueue(page(fileJson("2", "b.mp3", "root"), next = "p3"))

        val result = lister(maxPages = 2).list(account, "root")

        assertIs<DriveResult.Failure>(result)
    }

    @Test
    fun parentCycleDoesNotLoop() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val q = request.requestUrl!!.queryParameter("q")!!
                return if ("'A' in parents" in q) {
                    page(fileJson("root", "retour", "A", folder))
                } else {
                    page(fileJson("A", "a", "root", folder))
                }
            }
        }

        val result = lister().list(account, "root")

        assertIs<DriveResult.Success<List<DriveItem>>>(result)
        assertTrue(server.requestCount <= 3)
    }
}
