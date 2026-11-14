package io.github.camelbaggy.livresaudio.core.playback

import java.net.URLDecoder
import java.net.URLEncoder

/** URL of the Drive file download endpoint (`files.get?alt=media`), CLAUDE.md §5.1. */
const val DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files/"

/** A Drive file addressed by the player: which account reads it, which file, and its content hash. */
data class DriveMediaRef(val accountId: String, val fileId: String, val md5: String?)

/**
 * Internal URI scheme of the player: `drive://<accountId>/<driveFileId>?md5=<md5>`.
 *
 * The URI carries no secret (never a token). It is what the playlist and the cache layer see; the network
 * layer turns it into the real Drive URL ([toHttpUrl]). Pure functions, no Android type.
 */
object DriveMediaUri {
    const val SCHEME = "drive"
    private const val PREFIX = "$SCHEME://"
    private const val MD5_PARAM = "md5="
    private const val UNKNOWN_MD5 = "unknown"

    fun build(ref: DriveMediaRef): String {
        val base = "$PREFIX${encode(ref.accountId)}/${encode(ref.fileId)}"
        return if (ref.md5.isNullOrEmpty()) base else "$base?$MD5_PARAM${encode(ref.md5)}"
    }

    /** `null` when [uri] is not a well-formed `drive://` URI. */
    fun parse(uri: String): DriveMediaRef? {
        if (!uri.startsWith(PREFIX)) return null
        val rest = uri.removePrefix(PREFIX)
        val query = rest.substringAfter('?', missingDelimiterValue = "")
        val path = rest.substringBefore('?')
        val parts = path.split('/')
        if (parts.size != 2 || parts.any { it.isEmpty() }) return null
        val md5 = query.split('&').firstOrNull { it.startsWith(MD5_PARAM) }
            ?.removePrefix(MD5_PARAM)?.takeIf { it.isNotEmpty() }
        return DriveMediaRef(decode(parts[0]), decode(parts[1]), md5?.let(::decode))
    }

    /** Download URL; [filesUrl] is overridable for tests. */
    fun toHttpUrl(ref: DriveMediaRef, filesUrl: String = DRIVE_FILES_URL): String =
        "$filesUrl${encode(ref.fileId)}?alt=media"

    /** Stable cache key per file and content: `drive:<driveFileId>:<md5>` (CLAUDE.md §5.3). */
    fun cacheKey(ref: DriveMediaRef): String = "drive:${ref.fileId}:${ref.md5 ?: UNKNOWN_MD5}"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
}
