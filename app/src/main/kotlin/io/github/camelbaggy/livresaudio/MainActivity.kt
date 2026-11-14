package io.github.camelbaggy.livresaudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import io.github.camelbaggy.livresaudio.ui.EmptyScreen
import io.github.camelbaggy.livresaudio.ui.theme.LivresAudioTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LivresAudioTheme {
                EmptyScreen()
            }
        }
    }
}
