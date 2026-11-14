package io.github.camelbaggy.livresaudio.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val FallbackDark = darkColorScheme()
private val FallbackLight = lightColorScheme()

/**
 * Dark theme by default (CLAUDE.md §3); dynamic colors on Android 12+.
 * The light theme is only used when [useDarkTheme] is explicitly false.
 */
@Composable
fun LivresAudioTheme(useDarkTheme: Boolean = true, dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme: ColorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

            useDarkTheme -> FallbackDark

            else -> FallbackLight
        }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
