package io.github.camelbaggy.livresaudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import io.github.camelbaggy.livresaudio.e1.ComponentActivityAuthUi
import io.github.camelbaggy.livresaudio.e1.E1Screen
import io.github.camelbaggy.livresaudio.e1.E1ViewModel
import io.github.camelbaggy.livresaudio.ui.theme.LivresAudioTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // Launchers are registered here, before the Activity is STARTED.
    private val authUi = ComponentActivityAuthUi(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LivresAudioTheme {
                E1Screen(viewModel = hiltViewModel<E1ViewModel>(), authUi = authUi)
            }
        }
    }
}
