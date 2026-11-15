package io.github.camelbaggy.livresaudio.core.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import java.io.InterruptedIOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The `DataSpec` of the player's reads and of the pre-cacher's writes: same `drive://` URI, same explicit key
 * (`drive:<id>:<md5>`, also the `MediaItem`'s custom cache key), so the cache layer maps both onto the same
 * resource and the player consumes what the pre-cacher wrote.
 */
@UnstableApi
object PlaybackDataSpecs {
    fun forRange(ref: DriveMediaRef, position: Long, length: Long): DataSpec = DataSpec.Builder()
        .setUri(DriveMediaUri.build(ref))
        .setKey(DriveMediaUri.cacheKey(ref))
        .setPosition(position)
        .setLength(length)
        .setFlags(DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN)
        .build()

    fun wholeFile(ref: DriveMediaRef): DataSpec = forRange(ref, 0, C.LENGTH_UNSET.toLong())
}

/** [PreCacheSink] on Media3's `CacheWriter`, through the same cache chain as the player ([factory]). */
@UnstableApi
class CacheWriterSink(
    private val factory: CacheDataSource.Factory,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : PreCacheSink {
    override suspend fun cache(ref: DriveMediaRef, startByte: Long, lengthBytes: Long) {
        coroutineScope {
            val writer = CacheWriter(
                factory.createDataSource(),
                PlaybackDataSpecs.forRange(ref, startByte, lengthBytes),
                ByteArray(CacheWriter.DEFAULT_BUFFER_SIZE_BYTES),
                null
            )
            val copy = async(io) {
                try {
                    writer.cache()
                } catch (interrupted: InterruptedIOException) {
                    // `CacheWriter.cancel()` ends the copy this way: a cancellation, not a failure.
                    throw CancellationException("Pré-chargement annulé", interrupted)
                }
            }
            try {
                copy.await()
            } catch (cancelled: CancellationException) {
                // Stops the blocking copy; what is already written stays in the cache.
                writer.cancel()
                throw cancelled
            }
        }
    }
}
