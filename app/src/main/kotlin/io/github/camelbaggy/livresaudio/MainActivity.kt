package io.github.camelbaggy.livresaudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.AndroidEntryPoint
import io.github.camelbaggy.livresaudio.e1.ComponentActivityAuthUi
import io.github.camelbaggy.livresaudio.navigation.AppNavigation
import io.github.camelbaggy.livresaudio.ui.theme.LivresAudioTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // Launchers are registered here, before the Activity is STARTED.
    private val authUi = ComponentActivityAuthUi(this)

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LivresAudioTheme {
                AppNavigation(authUi)
            }
        }
    }
}
