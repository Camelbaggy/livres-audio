package io.github.camelbaggy.livresaudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage

private val DISPLAYABLE_SCHEMES = setOf("http", "https", "content", "file")

/** True when Coil can load the cover (`drive-…` and other private schemes cannot). Pure. */
fun isDisplayableCover(coverUri: String?): Boolean {
    if (coverUri.isNullOrBlank()) return false
    val scheme = coverUri.substringBefore(':', missingDelimiterValue = "").lowercase()
    return scheme in DISPLAYABLE_SCHEMES
}

/** Up to two initials from the first words of the title; `?` when there is no letter. Pure. */
fun coverInitials(title: String?): String {
    val letters = title.orEmpty().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        .take(2).map { it.first().uppercaseChar() }
    return if (letters.isEmpty()) "?" else letters.joinToString("")
}

/** Stable hue (0..359) derived from the book id. Pure. */
fun coverHue(bookId: String): Float = (bookId.hashCode().toLong().and(0x7fffffff) % 360).toFloat()

/**
 * Cover of a book: a generated one (initials on a color stable per book) with the real image over it when the URI
 * is loadable; if the load fails, the generated cover stays visible.
 */
@Composable
fun BookCover(bookId: String, title: String?, coverUri: String?, modifier: Modifier = Modifier) {
    val background = Color.hsv(coverHue(bookId), 0.45f, 0.42f)
    Box(modifier = modifier.background(background), contentAlignment = Alignment.Center) {
        Text(
            text = coverInitials(title),
            color = Color.White.copy(alpha = 0.92f),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
        if (isDisplayableCover(coverUri)) {
            AsyncImage(
                model = coverUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
