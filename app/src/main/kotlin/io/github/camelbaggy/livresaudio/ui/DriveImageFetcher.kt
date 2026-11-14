package io.github.camelbaggy.livresaudio.ui

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.github.camelbaggy.livresaudio.core.data.repo.DRIVE_IMAGE_SCHEME
import io.github.camelbaggy.livresaudio.core.drive.rest.AccountTag
import io.github.camelbaggy.livresaudio.core.drive.rest.DRIVE_BASE_URL
import io.github.camelbaggy.livresaudio.core.model.AccountId
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.FileSystem

/** A Drive image designated by a cover URI `drive-image://<accountId>/<driveFileId>`. */
data class DriveImageRef(val accountId: AccountId, val fileId: String)

/** Parses a `drive-image://<accountId>/<driveFileId>` URI; null for anything else. Pure. */
fun parseDriveImageUri(uri: String): DriveImageRef? {
    val prefix = "$DRIVE_IMAGE_SCHEME://"
    if (!uri.startsWith(prefix)) return null
    val parts = uri.removePrefix(prefix).split('/')
    if (parts.size != 2 || parts.any { it.isEmpty() }) return null
    return DriveImageRef(AccountId(parts[0]), parts[1])
}

/** Image size above which a download is refused (a cover is a few hundred KB). */
const val MAX_DRIVE_IMAGE_BYTES = 4L * 1024 * 1024

/**
 * Coil [Fetcher] for the `drive-image` scheme (CLAUDE.md §5.2 cover images, §5.4 offline display): downloads the
 * image with the authenticated Drive client (account carried by [AccountTag]) and keeps it in Coil's disk cache,
 * which is read first, so a cover already seen displays without network.
 */
class DriveImageFetcher(
    private val ref: DriveImageRef,
    private val client: Call.Factory,
    private val baseUrl: HttpUrl,
    private val diskCache: DiskCache?,
    private val readCache: Boolean,
    private val writeCache: Boolean,
    private val maxBytes: Long
) : Fetcher {
    private val key = "$DRIVE_IMAGE_SCHEME:${ref.accountId.value}:${ref.fileId}"

    override suspend fun fetch(): FetchResult {
        val cache = diskCache
        if (cache != null && readCache) {
            cache.openSnapshot(key)?.let { snapshot ->
                val source = ImageSource(
                    file = snapshot.data,
                    fileSystem = cache.fileSystem,
                    diskCacheKey = key,
                    closeable = snapshot
                )
                return SourceFetchResult(source, null, DataSource.DISK)
            }
        }
        val (bytes, mime) = download()
        return SourceFetchResult(store(cache.takeIf { writeCache }, bytes), mime, DataSource.NETWORK)
    }

    private suspend fun download(): Pair<ByteArray, String?> {
        val url = baseUrl.newBuilder()
            .addPathSegment("files")
            .addPathSegment(ref.fileId)
            .addQueryParameter("alt", "media")
            .build()
        val request = Request.Builder()
            .url(url)
            .tag(AccountTag::class.java, AccountTag(ref.accountId))
            .build()
        val response = client.newCall(request).await()
        return withContext(Dispatchers.IO) {
            response.use {
                if (!it.isSuccessful) throw IOException("Image Drive : HTTP ${it.code}")
                val body = it.body
                if (body.contentLength() > maxBytes) throw IOException("Image Drive trop grosse")
                val source = body.source()
                source.request(maxBytes + 1)
                if (source.buffer.size > maxBytes) throw IOException("Image Drive trop grosse")
                source.buffer.readByteArray() to it.header("Content-Type")?.substringBefore(';')?.trim()
            }
        }
    }

    /** Writes [bytes] to the disk cache when possible; the image is served from memory otherwise. */
    private fun store(cache: DiskCache?, bytes: ByteArray): ImageSource {
        if (cache != null) {
            val editor = cache.openEditor(key)
            if (editor != null) {
                try {
                    cache.fileSystem.write(editor.data) { write(bytes) }
                    val snapshot = editor.commitAndOpenSnapshot()
                    if (snapshot != null) {
                        return ImageSource(
                            file = snapshot.data,
                            fileSystem = cache.fileSystem,
                            diskCacheKey = key,
                            closeable = snapshot
                        )
                    }
                } catch (_: IOException) {
                    runCatching { editor.abort() }
                }
            }
        }
        return ImageSource(Buffer().write(bytes), FileSystem.SYSTEM)
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) = continuation.resume(response)
        }
    )
}

/** Registered in the app's `ImageLoader` for [Uri]s of the `drive-image` scheme. */
class DriveImageFetcherFactory(
    private val client: Call.Factory,
    private val baseUrl: HttpUrl = DRIVE_BASE_URL.toHttpUrl(),
    private val maxBytes: Long = MAX_DRIVE_IMAGE_BYTES
) : Fetcher.Factory<Uri> {
    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
        val ref = parseDriveImageUri(data.toString()) ?: return null
        return DriveImageFetcher(
            ref = ref,
            client = client,
            baseUrl = baseUrl,
            diskCache = imageLoader.diskCache,
            readCache = options.diskCachePolicy.readEnabled,
            writeCache = options.diskCachePolicy.writeEnabled,
            maxBytes = maxBytes
        )
    }
}
