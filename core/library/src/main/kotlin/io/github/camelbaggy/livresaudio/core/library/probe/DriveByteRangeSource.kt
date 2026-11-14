package io.github.camelbaggy.livresaudio.core.library.probe

import io.github.camelbaggy.livresaudio.core.drive.rest.AccountTag
import io.github.camelbaggy.livresaudio.core.drive.rest.AuthFailedException
import io.github.camelbaggy.livresaudio.core.drive.rest.AuthInterceptor
import io.github.camelbaggy.livresaudio.core.drive.rest.DRIVE_BASE_URL
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveError
import io.github.camelbaggy.livresaudio.core.drive.rest.RetryInterceptor
import io.github.camelbaggy.livresaudio.core.drive.rest.TokenAuthenticator
import io.github.camelbaggy.livresaudio.core.drive.rest.describe
import io.github.camelbaggy.livresaudio.core.drive.rest.mapHttpError
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.AuthResult
import io.github.camelbaggy.livresaudio.core.model.ByteRangeSource
import io.github.camelbaggy.livresaudio.core.model.DriveAuthProvider
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** A Drive read failed: [error] is typed so that the caller can tell a network blip from a lost file. */
class DriveReadException(val error: DriveError) : IOException(error.describe())

/** Opens a [ByteRangeSource] on a Drive file. A seam for tests. */
fun interface ProbeSourceFactory {
    fun open(accountId: String, driveFileId: String, sizeBytes: Long?): ByteRangeSource
}

/** HTTP client of the probe: bearer token of the tagged account, renewal on 401, backoff on 5xx / quota. */
object ProbeHttp {
    fun createClient(auth: () -> DriveAuthProvider, base: OkHttpClient = OkHttpClient()): OkHttpClient =
        base.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor())
            .addInterceptor(AuthInterceptor(auth))
            .authenticator(TokenAuthenticator(auth))
            .build()

    private const val CONNECT_TIMEOUT_S = 10L
    private const val READ_TIMEOUT_S = 30L
}

class DriveProbeSourceFactory(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = DRIVE_BASE_URL.toHttpUrl()
) : ProbeSourceFactory {
    override fun open(accountId: String, driveFileId: String, sizeBytes: Long?): ByteRangeSource =
        DriveByteRangeSource(client, AccountId(accountId), driveFileId, sizeBytes, baseUrl)
}

/**
 * Reads byte ranges of a Drive file (`files.get?alt=media` with a `Range` header) through the authenticated
 * client of `core:drive` (account carried by [AccountTag]). The size comes from Drive's listing.
 *
 * Reads go through an in-memory LRU of [blockSize] blocks, so the many small reads of a parser cost few
 * round trips; consecutive missing blocks of one read are fetched in a single request. Failures are
 * [DriveReadException]s (an [IOException]).
 */
class DriveByteRangeSource(
    private val client: OkHttpClient,
    private val accountId: AccountId,
    private val fileId: String,
    private val sizeBytes: Long?,
    private val baseUrl: HttpUrl = DRIVE_BASE_URL.toHttpUrl(),
    private val blockSize: Int = DEFAULT_BLOCK_SIZE,
    private val maxCachedBlocks: Int = DEFAULT_CACHED_BLOCKS
) : ByteRangeSource {
    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > maxCachedBlocks
    }

    /** Number of HTTP requests made (diagnostics, tests). */
    var requestCount = 0
        private set

    override suspend fun size(): Long? = sizeBytes

    override suspend fun read(offset: Long, length: Int): ByteArray = mutex.withLock {
        if (offset < 0 || length <= 0) return@withLock ByteArray(0)
        var end = offset + length
        if (sizeBytes != null) end = minOf(end, sizeBytes)
        if (offset >= end) return@withLock ByteArray(0)
        val first = offset / blockSize
        val last = (end - 1) / blockSize
        val got = HashMap<Long, ByteArray>()
        var block = first
        while (block <= last) {
            val cached = cache[block]
            if (cached != null) {
                got[block] = cached
                block++
                continue
            }
            var runEnd = block
            while (runEnd + 1 <= last && cache[runEnd + 1] == null) runEnd++
            val data = fetch(block * blockSize, (runEnd + 1) * blockSize.toLong())
            var index = block
            var pos = 0
            while (pos < data.size) {
                val part = data.copyOfRange(pos, minOf(data.size, pos + blockSize))
                got[index] = part
                cache[index] = part
                index++
                pos += blockSize
            }
            block = runEnd + 1
        }
        assemble(got, first, last, offset, end)
    }

    private fun assemble(got: Map<Long, ByteArray>, first: Long, last: Long, offset: Long, end: Long): ByteArray {
        val out = ByteArrayOutputStream((end - offset).toInt())
        for (b in first..last) {
            val data = got[b] ?: break
            val blockStart = b * blockSize
            val from = (maxOf(offset, blockStart) - blockStart).toInt()
            val to = (minOf(end, blockStart + data.size) - blockStart).toInt()
            if (from >= to) break
            out.write(data, from, to - from)
            if (data.size < blockSize) break // end of file
        }
        return out.toByteArray()
    }

    /** Bytes `[start, endExclusive)`, shorter at the end of the file. */
    private suspend fun fetch(start: Long, endExclusive: Long): ByteArray {
        val stop = if (sizeBytes != null) minOf(endExclusive, sizeBytes) else endExclusive
        if (start >= stop) return ByteArray(0)
        val url = baseUrl.newBuilder()
            .addPathSegment("files")
            .addPathSegment(fileId)
            .addQueryParameter("alt", "media")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-${stop - 1}")
            .tag(AccountTag::class.java, AccountTag(accountId))
            .build()
        requestCount++
        val response = execute(request)
        return response.use { read(it, start, (stop - start)) }
    }

    private fun read(response: Response, start: Long, want: Long): ByteArray = try {
        when {
            response.code == 416 -> ByteArray(0)

            response.isSuccessful -> {
                val body = response.body
                val source = body.source()
                if (response.code == 200 && start > 0) {
                    // The server ignored the Range header: skipping a large prefix would defeat the purpose.
                    if (start > MAX_SKIP_BYTES) throw DriveReadException(DriveError.Unexpected("Range ignoré"))
                    source.skip(start)
                }
                source.request(want)
                source.buffer.readByteArray(minOf(source.buffer.size, want))
            }

            else -> throw DriveReadException(mapHttpError(response.code, response.body.string()))
        }
    } catch (e: DriveReadException) {
        throw e
    } catch (e: java.io.EOFException) {
        ByteArray(0)
    } catch (e: IOException) {
        throw DriveReadException(DriveError.NetworkUnavailable)
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(DriveReadException(errorOf(e)))
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }

    private fun errorOf(e: IOException): DriveError = if (e is AuthFailedException) {
        when (e.result) {
            AuthResult.NetworkUnavailable -> DriveError.NetworkUnavailable
            AuthResult.NeedsUserAction, AuthResult.Cancelled -> DriveError.AuthRequired
            else -> DriveError.Unexpected("jeton indisponible")
        }
    } else {
        DriveError.NetworkUnavailable
    }

    companion object {
        const val DEFAULT_BLOCK_SIZE = 64 * 1024
        const val DEFAULT_CACHED_BLOCKS = 32
        private const val MAX_SKIP_BYTES = 2L * 1024 * 1024
    }
}
