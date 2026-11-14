package io.github.camelbaggy.livresaudio.e1

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.camelbaggy.livresaudio.R
import io.github.camelbaggy.livresaudio.core.drive.auth.ActivityAuthUi
import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.core.model.ConnectedAccount
import kotlinx.coroutines.launch

private val ButtonHeight = 48.dp

@Composable
fun E1Screen(viewModel: E1ViewModel, authUi: ActivityAuthUi, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var askRole by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(Modifier.padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.e1_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.e1_intro), style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = { askRole = true },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = ButtonHeight)
                    ) { Text(stringResource(R.string.e1_add_account)) }
                    if (state.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.e1_busy), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (state.accounts.isEmpty()) {
                item { Text(stringResource(R.string.e1_no_account)) }
            }
            items(state.accounts, key = { it.id.value }) { account ->
                AccountCard(account, state.busy, viewModel)
            }
            item {
                Text(stringResource(R.string.e1_log_title), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("journal E1", state.log.asText()))
                                )
                            }
                        },
                        enabled = state.log.isNotEmpty(),
                        modifier = Modifier.heightIn(min = ButtonHeight)
                    ) { Text(stringResource(R.string.e1_copy_log)) }
                    TextButton(
                        onClick = viewModel::clearLog,
                        enabled = state.log.isNotEmpty(),
                        modifier = Modifier.heightIn(min = ButtonHeight)
                    ) { Text(stringResource(R.string.e1_clear_log)) }
                }
            }
            if (state.log.isEmpty()) {
                item { Text(stringResource(R.string.e1_log_empty), style = MaterialTheme.typography.bodySmall) }
            }
            items(state.log.asReversed()) { line ->
                SelectionContainer {
                    Text(
                        text = "${line.time}  ${if (line.ok) "OK" else "ÉCHEC"}  ${line.text}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (line.ok) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.error
                    )
                }
            }
            item { Text("", Modifier.padding(bottom = 24.dp)) }
        }
    }

    if (askRole) {
        AlertDialog(
            onDismissRequest = { askRole = false },
            title = { Text(stringResource(R.string.e1_role_dialog_title)) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            askRole = false
                            viewModel.addAccount(authUi, AccountRole.SHARED)
                        },
                        modifier = Modifier.heightIn(min = ButtonHeight)
                    ) { Text(stringResource(R.string.e1_role_shared)) }
                    TextButton(
                        onClick = {
                            askRole = false
                            viewModel.addAccount(authUi, AccountRole.PERSONAL)
                        },
                        modifier = Modifier.heightIn(min = ButtonHeight)
                    ) { Text(stringResource(R.string.e1_role_personal)) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { askRole = false }) { Text(stringResource(R.string.e1_cancel)) } }
        )
    }
}

@Composable
private fun AccountCard(account: ConnectedAccount, busy: Boolean, viewModel: E1ViewModel) {
    val role = stringResource(
        if (account.role == AccountRole.SHARED) R.string.e1_role_shared_short else R.string.e1_role_personal_short
    )
    val profile = if (account.isProfile) " · ${stringResource(R.string.e1_profile_marker)}" else ""
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(account.email, style = MaterialTheme.typography.titleSmall)
            Text("$role$profile", style = MaterialTheme.typography.bodySmall)
            ActionButton(R.string.e1_list_root, !busy) { viewModel.listRoot(account) }
            ActionButton(R.string.e1_silent_token, !busy) { viewModel.silentToken(account) }
            ActionButton(R.string.e1_write_appdata, !busy) { viewModel.writeAppData(account) }
            ActionButton(R.string.e1_write_drive_file, !busy) { viewModel.writeDriveFile(account) }
            TextButton(
                onClick = { viewModel.removeAccount(account) },
                enabled = !busy,
                modifier = Modifier.heightIn(min = ButtonHeight)
            ) { Text(stringResource(R.string.e1_remove)) }
        }
    }
}

@Composable
private fun ActionButton(label: Int, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = ButtonHeight)
    ) { Text(stringResource(label)) }
}
