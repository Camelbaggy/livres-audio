package io.github.camelbaggy.livresaudio.core.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/** Disk cache used by the player's `stream/` chain (CLAUDE.md §5.4). Only one instance per directory. */
@UnstableApi
class PlaybackCache(context: Context, maxBytes: Long = DEFAULT_MAX_BYTES) {
    val cache: SimpleCache = SimpleCache(
        File(context.cacheDir, DIRECTORY),
        LeastRecentlyUsedCacheEvictor(maxBytes),
        StandaloneDatabaseProvider(context)
    )

    /** Bytes cached for one file (`C.LENGTH_UNSET` = to the end of the resource). */
    fun cachedBytes(key: String): Long = cache.getCachedBytes(key, 0, C.LENGTH_UNSET.toLong())

    fun totalBytes(): Long = cache.cacheSpace

    /** Removes everything. The caller must have stopped playback first. */
    fun clear() {
        cache.keys.toList().forEach { cache.removeResource(it) }
    }

    companion object {
        const val DIRECTORY = "stream"
        const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024
    }
}
