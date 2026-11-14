package io.github.camelbaggy.livresaudio.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.camelbaggy.livresaudio.R

/** Provisional library (task 1.10 replaces it). */
@Composable
fun LibraryPlaceholderScreen(
    viewModel: LibraryPlaceholderViewModel,
    onEditSetup: () -> Unit,
    onOpenDiagnostic: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.library_soon), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.library_roots_title), style = MaterialTheme.typography.titleMedium)
            state.roots.forEach {
                Text(stringResource(R.string.roots_item, it.name, it.accountEmail.orEmpty()))
            }
            OutlinedButton(onClick = onEditSetup, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.library_edit_setup))
            }
            OutlinedButton(onClick = onOpenDiagnostic, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.library_diagnostic))
            }
        }
    }
}
