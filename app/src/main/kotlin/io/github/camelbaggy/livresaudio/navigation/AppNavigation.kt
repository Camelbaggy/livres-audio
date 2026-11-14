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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.camelbaggy.livresaudio.accounts.AccountsScreen
import io.github.camelbaggy.livresaudio.book.BookDetailScreen
import io.github.camelbaggy.livresaudio.book.BookDetailViewModel
import io.github.camelbaggy.livresaudio.core.drive.auth.ActivityAuthUi
import io.github.camelbaggy.livresaudio.diagnostic.DiagnosticMenuScreen
import io.github.camelbaggy.livresaudio.e1.E1Screen
import io.github.camelbaggy.livresaudio.e1.E1ViewModel
import io.github.camelbaggy.livresaudio.e2.E2Screen
import io.github.camelbaggy.livresaudio.e2.E2ViewModel
import io.github.camelbaggy.livresaudio.library.LibraryScreen
import io.github.camelbaggy.livresaudio.onboarding.OnboardingScreen
import io.github.camelbaggy.livresaudio.player.PlayerScreen
import io.github.camelbaggy.livresaudio.settings.ListeningSettingsScreen

object Routes {
    const val ONBOARDING = "accueil"
    const val LIBRARY = "bibliotheque"
    const val BOOK = "livre/{${BookDetailViewModel.ARG_BOOK_ID}}"
    const val PLAYER = "lecteur"
    const val SETTINGS = "reglages"
    const val ACCOUNTS = "reglages/comptes"
    const val DIAGNOSTIC = "diagnostic"
    const val E1 = "diagnostic/e1"
    const val E2 = "diagnostic/e2"

    fun book(bookId: String) = "livre/${android.net.Uri.encode(bookId)}"
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
            LibraryScreen(
                viewModel = hiltViewModel(),
                playerViewModel = hiltViewModel(),
                onOpenBook = { navController.navigate(Routes.book(it)) },
                onOpenPlayer = { navController.navigate(Routes.PLAYER) { launchSingleTop = true } },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onEditSetup = { navController.navigate(Routes.ACCOUNTS) },
                onOpenDiagnostic = { navController.navigate(Routes.DIAGNOSTIC) }
            )
        }
        composable(
            Routes.BOOK,
            arguments = listOf(navArgument(BookDetailViewModel.ARG_BOOK_ID) { type = NavType.StringType })
        ) {
            BookDetailScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStack() },
                onOpenPlayer = { navController.navigate(Routes.PLAYER) { launchSingleTop = true } }
            )
        }
        composable(Routes.PLAYER) {
            PlayerScreen(viewModel = hiltViewModel(), onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            ListeningSettingsScreen(
                viewModel = hiltViewModel(),
                onOpenAccounts = { navController.navigate(Routes.ACCOUNTS) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.ACCOUNTS) {
            AccountsScreen(
                viewModel = hiltViewModel(),
                authUi = authUi,
                onAddAccount = { navController.navigate(Routes.ONBOARDING) },
                onBack = { navController.popBackStack() }
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
