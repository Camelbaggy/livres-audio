package io.github.camelbaggy.livresaudio.e2

import java.util.Locale

/** Escapes a value for a single-quoted Drive `q` string: backslash and apostrophe. */
fun escapeDriveQueryValue(text: String): String = text.replace("\\", "\\\\").replace("'", "\\'")

/**
 * Drive query for audio files whose name contains [text] (all audio files if blank).
 * Note: Drive's `name contains` matches whole word prefixes, not arbitrary substrings.
 */
fun audioSearchQuery(text: String): String {
    val name = text.trim()
    val nameClause = if (name.isEmpty()) "" else " and name contains '${escapeDriveQueryValue(name)}'"
    return "mimeType contains 'audio/'$nameClause and trashed=false"
}

/** `mm:ss`, or `h:mm:ss` from one hour; negative or unknown values show `--:--`. */
fun formatDuration(ms: Long?): String {
    if (ms == null || ms < 0) return "--:--"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

/** Size in decimal megabytes with one decimal, e.g. `312,5 Mo` (French decimal comma). */
fun formatMegabytes(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "? Mo"
    return String.format(Locale.FRANCE, "%.1f Mo", bytes / 1_000_000.0)
}
