package io.github.camelbaggy.livresaudio.util

import java.util.Locale

/** `m:ss` or `h:mm:ss`: clock-style time, for positions. Negative values count as 0. */
fun formatClock(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1_000)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

/** `3 h 05 min`, `45 min`, `30 s`: spoken-style duration, for totals and remaining time. */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1_000)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    return when {
        hours > 0 -> String.format(Locale.ROOT, "%d h %02d min", hours, minutes)
        minutes > 0 -> "$minutes min"
        else -> "${totalSeconds % 60} s"
    }
}

/** `1`, `1,25`, `0,5`: speed with a French decimal comma and no useless zeros. */
fun formatSpeed(speed: Float): String {
    val hundredths = Math.round(speed * 100)
    val whole = hundredths / 100
    val fraction = hundredths % 100
    return when {
        fraction == 0 -> "$whole"
        fraction % 10 == 0 -> "$whole,${fraction / 10}"
        else -> String.format(Locale.ROOT, "%d,%02d", whole, fraction)
    }
}
