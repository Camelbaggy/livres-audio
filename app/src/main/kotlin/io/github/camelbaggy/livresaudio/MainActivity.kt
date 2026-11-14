package io.github.camelbaggy.livresaudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.AndroidEntryPoint
import io.github.camelbaggy.livresaudio.e1.ComponentActivityAuthUi
import io.github.camelbaggy.livresaudio.e1.E1Screen
import io.github.camelbaggy.livresaudio.e1.E1ViewModel
import io.github.camelbaggy.livresaudio.e2.E2Screen
import io.github.camelbaggy.livresaudio.e2.E2ViewModel
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
                // Temporary trial navigation (lot 0): a single state is enough, no Navigation Compose.
                var showE2 by rememberSaveable { mutableStateOf(false) }
                if (showE2) {
                    E2Screen(viewModel = hiltViewModel<E2ViewModel>(), onBack = { showE2 = false })
                } else {
                    E1Screen(
                        viewModel = hiltViewModel<E1ViewModel>(),
                        authUi = authUi,
                        onOpenE2 = { showE2 = true }
                    )
                }
            }
        }
    }
}
