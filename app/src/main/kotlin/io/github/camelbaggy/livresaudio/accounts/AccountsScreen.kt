package io.github.camelbaggy.livresaudio.accounts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.camelbaggy.livresaudio.R
import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.core.model.AuthUi
import io.github.camelbaggy.livresaudio.onboarding.ConnectError
import io.github.camelbaggy.livresaudio.onboarding.FolderError
import io.github.camelbaggy.livresaudio.ui.PlayerIcons

private val MinTarget = 48.dp

/** "Comptes et dossiers": no logic here, everything goes through [AccountsViewModel]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    viewModel: AccountsViewModel,
    authUi: AuthUi,
    onAddAccount: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.browsing) { viewModel.backInChooser() }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.accounts_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = { if (state.browsing) viewModel.closeChooser() else onBack() },
                        modifier = Modifier.size(MinTarget)
                    ) { Icon(PlayerIcons.ArrowBack, stringResource(R.string.book_back)) }
                }
            )
        }
    ) { padding ->
        if (state.browsing) {
            ChooserContent(state, viewModel, Modifier.padding(padding))
        } else {
            AccountsContent(state, viewModel, authUi, onAddAccount, Modifier.padding(padding))
        }
    }
    state.pendingRemoval?.let { item ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRemoval,
            title = { Text(stringResource(R.string.accounts_remove_title, item.account.email)) },
            text = { Text(stringResource(R.string.accounts_remove_text)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRemoval, modifier = Modifier.heightIn(min = MinTarget)) {
                    Text(stringResource(R.string.accounts_remove_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRemoval, modifier = Modifier.heightIn(min = MinTarget)) {
                    Text(stringResource(R.string.accounts_remove_cancel))
                }
            }
        )
    }
}

@Composable
private fun AccountsContent(
    state: AccountsUiState,
    viewModel: AccountsViewModel,
    authUi: AuthUi,
    onAddAccount: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        state.message?.let { message ->
            item { MessageCard(message, viewModel::dismissMessage) }
        }
        if (state.accounts.isEmpty()) {
            item { Text(stringResource(R.string.accounts_none), style = MaterialTheme.typography.bodyLarge) }
        }
        items(state.accounts, key = { it.account.id.value }) { item ->
            AccountCard(item, state, viewModel, authUi)
        }
        item {
            OutlinedButton(
                onClick = onAddAccount,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget)
            ) { Text(stringResource(R.string.summary_add_account)) }
        }
    }
}

@Composable
private fun MessageCard(message: AccountsMessage, onDismiss: () -> Unit) {
    val text = when (message) {
        is AccountsMessage.Reconnected -> stringResource(R.string.accounts_reconnected, message.email)
        is AccountsMessage.ReconnectFailed -> connectErrorText(message.error)
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTarget)) {
                Text(stringResource(R.string.accounts_dismiss))
            }
        }
    }
}

@Composable
private fun connectErrorText(error: ConnectError): String = when (error) {
    ConnectError.SharedAlreadyConnected -> stringResource(R.string.error_shared_exists)
    ConnectError.Cancelled -> stringResource(R.string.error_cancelled)
    ConnectError.NeedsUserAction -> stringResource(R.string.error_needs_action)
    ConnectError.NetworkUnavailable -> stringResource(R.string.error_network)
    is ConnectError.Failure -> stringResource(R.string.error_failure, error.message)
}

@Composable
private fun AccountCard(item: AccountItem, state: AccountsUiState, viewModel: AccountsViewModel, authUi: AuthUi) {
    val account = item.account
    val role = stringResource(
        if (account.role == AccountRole.SHARED) R.string.accounts_role_shared else R.string.accounts_role_personal
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(account.email, style = MaterialTheme.typography.titleMedium)
            Text(role, style = MaterialTheme.typography.bodyMedium)
            if (account.isProfile) {
                Text(
                    stringResource(R.string.accounts_profile),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(stringResource(R.string.roots_title), style = MaterialTheme.typography.titleSmall)
            if (item.roots.isEmpty()) {
                Text(stringResource(R.string.roots_empty), style = MaterialTheme.typography.bodyMedium)
            }
            item.roots.forEach { root ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(root.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    val description = stringResource(R.string.roots_remove_description, root.name)
                    TextButton(
                        onClick = { viewModel.removeRoot(root.id) },
                        modifier = Modifier.heightIn(min = MinTarget).semantics { contentDescription = description }
                    ) { Text(stringResource(R.string.roots_remove)) }
                }
            }
            TextButton(
                onClick = { viewModel.startAddFolder(account.id) },
                modifier = Modifier.heightIn(min = MinTarget)
            ) { Text(stringResource(R.string.accounts_add_folder)) }
            val reconnecting = state.reconnecting == account.id
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { viewModel.reconnect(account.id, authUi) },
                    enabled = state.reconnecting == null,
                    modifier = Modifier.heightIn(min = MinTarget)
                ) { Text(stringResource(R.string.accounts_reconnect)) }
                if (reconnecting) CircularProgressIndicator(Modifier.size(24.dp))
            }
            TextButton(
                onClick = { viewModel.requestRemoval(account.id) },
                modifier = Modifier.heightIn(min = MinTarget)
            ) { Text(stringResource(R.string.accounts_remove), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ChooserContent(state: AccountsUiState, viewModel: AccountsViewModel, modifier: Modifier = Modifier) {
    val browser = state.browser
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.folders_intro, browser.account?.email.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium
                )
                val path = browser.path.joinToString(" › ") { it.name }
                val description = stringResource(R.string.folders_breadcrumb, path)
                Text(
                    path,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }
                )
                if (browser.path.size > 1) {
                    OutlinedButton(
                        onClick = { viewModel.up() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget)
                    ) { Text(stringResource(R.string.folders_up)) }
                }
                val chosen = state.currentFolderChosen
                Button(
                    onClick = viewModel::chooseCurrentFolder,
                    enabled = browser.current != null && !chosen,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget)
                ) { Text(stringResource(if (chosen) R.string.folders_chosen else R.string.folders_choose)) }
                OutlinedButton(
                    onClick = viewModel::closeChooser,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget)
                ) { Text(stringResource(R.string.accounts_chooser_done)) }
            }
        }
        item { ChooserStatus(state, viewModel) }
        items(browser.folders, key = { it.id }) { folder ->
            val open = stringResource(R.string.folders_open, folder.name)
            Text(
                folder.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget)
                    .clickable { viewModel.openFolder(folder) }
                    .padding(vertical = 12.dp)
                    .semantics { contentDescription = open }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun ChooserStatus(state: AccountsUiState, viewModel: AccountsViewModel) {
    val browser = state.browser
    when {
        browser.loading -> Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(Modifier.padding(4.dp))
            Text(stringResource(R.string.folders_loading))
        }

        browser.error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val text = when (val e = browser.error) {
                FolderError.NetworkUnavailable -> stringResource(R.string.folders_error_network)
                FolderError.AuthRequired -> stringResource(R.string.folders_error_auth)
                is FolderError.Other -> stringResource(R.string.folders_error_other, e.description)
                null -> ""
            }
            Text(text, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = viewModel::retry, modifier = Modifier.heightIn(min = MinTarget)) {
                Text(stringResource(R.string.folders_retry))
            }
        }

        browser.folders.isEmpty() -> Text(stringResource(R.string.folders_empty))
    }
}
