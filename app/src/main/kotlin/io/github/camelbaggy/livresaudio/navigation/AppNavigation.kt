package io.github.camelbaggy.livresaudio.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.camelbaggy.livresaudio.core.drive.auth.ActivityAuthUi
import io.github.camelbaggy.livresaudio.diagnostic.DiagnosticMenuScreen
import io.github.camelbaggy.livresaudio.e1.E1Screen
import io.github.camelbaggy.livresaudio.e1.E1ViewModel
import io.github.camelbaggy.livresaudio.e2.E2Screen
import io.github.camelbaggy.livresaudio.e2.E2ViewModel
import io.github.camelbaggy.livresaudio.library.LibraryPlaceholderScreen
import io.github.camelbaggy.livresaudio.onboarding.OnboardingScreen

object Routes {
    const val ONBOARDING = "accueil"
    const val LIBRARY = "bibliotheque"
    const val DIAGNOSTIC = "diagnostic"
    const val E1 = "diagnostic/e1"
    const val E2 = "diagnostic/e2"
}

@UnstableApi
@Composable
fun AppNavigation(authUi: ActivityAuthUi) {
    val start = hiltViewModel<StartViewModel>().destination.collectAsStateWithLifecycle().value
    if (start == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = if (start == AppDestination.Library) Routes.LIBRARY else Routes.ONBOARDING
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                viewModel = hiltViewModel(),
                authUi = authUi,
                onFinished = {
                    navController.navigate(Routes.LIBRARY) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Routes.LIBRARY) {
            LibraryPlaceholderScreen(
                viewModel = hiltViewModel(),
                onEditSetup = { navController.navigate(Routes.ONBOARDING) },
                onOpenDiagnostic = { navController.navigate(Routes.DIAGNOSTIC) }
            )
        }
        composable(Routes.DIAGNOSTIC) {
            DiagnosticMenuScreen(
                onOpenE1 = { navController.navigate(Routes.E1) },
                onOpenE2 = { navController.navigate(Routes.E2) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.E1) {
            E1Screen(
                viewModel = hiltViewModel<E1ViewModel>(),
                authUi = authUi,
                onOpenE2 = { navController.navigate(Routes.E2) }
            )
        }
        composable(Routes.E2) {
            E2Screen(viewModel = hiltViewModel<E2ViewModel>(), onBack = { navController.popBackStack() })
        }
    }
}
