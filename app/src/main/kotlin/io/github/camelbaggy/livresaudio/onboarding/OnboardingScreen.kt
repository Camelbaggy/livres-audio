package io.github.camelbaggy.livresaudio.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.camelbaggy.livresaudio.core.model.ConnectedAccount

private val MinTarget = 48.dp

/** Onboarding flow (CLAUDE.md §5.5 point 1). No logic here: everything goes through [OnboardingViewModel]. */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    authUi: AuthUi,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) { if (state.finished) onFinished() }
    BackHandler(enabled = state.step != OnboardingStep.Welcome && state.step != OnboardingStep.Summary) {
        viewModel.back()
    }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state.step) {
            OnboardingStep.Welcome -> WelcomeStep(onConnect = viewModel::start)
            OnboardingStep.ChooseRole -> RoleStep(state, authUi, viewModel)
            OnboardingStep.Connecting -> ConnectingStep()
            OnboardingStep.ChooseFolders -> FoldersStep(state, viewModel)
            OnboardingStep.Summary -> SummaryStep(state, viewModel)
        }
    }
}

@Composable
private fun StepColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) { content() }
}

@Composable
private fun WelcomeStep(onConnect: () -> Unit) = StepColumn {
    Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineMedium)
    Text(stringResource(R.string.welcome_text), style = MaterialTheme.typography.bodyLarge)
    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget)) {
        Text(stringResource(R.string.welcome_connect))
    }
}

@Composable
private fun ErrorText(error: ConnectError) {
    val text = when (error) {
        ConnectError.SharedAlreadyConnected -> stringResource(R.string.error_shared_exists)
        ConnectError.Cancelled -> stringResource(R.string.error_cancelled)
        ConnectError.NeedsUserAction -> stringResource(R.string.error_needs_action)
        ConnectError.NetworkUnavailable -> stringResource(R.string.error_network)
        is ConnectError.Failure -> stringResource(R.string.error_failure, error.message)
    }
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun RoleStep(state: OnboardingUiState, authUi: AuthUi, viewModel: OnboardingViewModel) = StepColumn {
    Text(stringResource(R.string.role_title), style = MaterialTheme.typography.headlineMedium)
    Text(stringResource(R.string.role_intro), style = MaterialTheme.typography.bodyMedium)
    state.error?.let { ErrorText(it) }
    RoleCard(
        title = stringResource(R.string.role_shared_title),
        text = if (state.sharedRoleAvailable) {
            stringResource(R.string.role_shared_text)
        } else {
            stringResource(R.string.role_shared_taken)
        },
        enabled = state.sharedRoleAvailable,
        onClick = { viewModel.chooseRole(AccountRole.SHARED, authUi) }
    )
    RoleCard(
        title = stringResource(R.string.role_personal_title),
        text = stringResource(R.string.role_personal_text),
        enabled = true,
        onClick = { viewModel.chooseRole(AccountRole.PERSONAL, authUi) }
    )
    TextButton(onClick = { viewModel.back() }, modifier = Modifier.heightIn(min = MinTarget)) {
        Text(stringResource(R.string.role_back))
    }
}

@Composable
private fun RoleCard(title: String, text: String, enabled: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "$title. $text" }
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
            )
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ConnectingStep() = StepColumn {
    val description = stringResource(R.string.connecting)
    CircularProgressIndicator(Modifier.semantics { contentDescription = description })
    Text(description, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun FoldersStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val browser = state.browser
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column(Modifier.padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.folders_title), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.folders_intro, browser.account?.email.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium
                )
                Breadcrumbs(browser, viewModel)
            }
        }
        item {
            val chosen = state.currentFolderChosen
            Button(
                onClick = viewModel::chooseCurrentFolder,
                enabled = browser.current != null && !chosen,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget)
            ) {
                Text(stringResource(if (chosen) R.string.folders_chosen else R.string.folders_choose))
            }
        }
        item { FolderListHeader(browser, viewModel) }
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
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.roots_title), style = MaterialTheme.typography.titleMedium)
                RootsList(state.roots, viewModel)
                Button(
                    onClick = viewModel::showSummary,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget)
                ) { Text(stringResource(R.string.folders_continue)) }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(browser: FolderBrowserState, viewModel: OnboardingViewModel) {
    val path = browser.path.joinToString(" › ") { it.name }
    val description = stringResource(R.string.folders_breadcrumb, path)
    Text(
        text = path,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }
    )
    if (browser.path.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            browser.path.dropLast(1).forEachIndexed { index, crumb ->
                TextButton(onClick = { viewModel.goToCrumb(index) }, modifier = Modifier.heightIn(min = MinTarget)) {
                    Text(crumb.name, maxLines = 1)
                }
            }
        }
        OutlinedButton(
            onClick = { viewModel.up() },
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget)
        ) { Text(stringResource(R.string.folders_up)) }
    }
}

@Composable
private fun FolderListHeader(browser: FolderBrowserState, viewModel: OnboardingViewModel) {
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

@Composable
private fun RootsList(roots: List<ChosenRoot>, viewModel: OnboardingViewModel) {
    if (roots.isEmpty()) {
        Text(stringResource(R.string.roots_empty), style = MaterialTheme.typography.bodyMedium)
        return
    }
    roots.forEach { root ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.roots_item, root.name, root.accountEmail.orEmpty()),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            val description = stringResource(R.string.roots_remove_description, root.name)
            TextButton(
                onClick = { viewModel.removeRoot(root.id) },
                modifier = Modifier.heightIn(min = MinTarget).semantics { contentDescription = description }
            ) { Text(stringResource(R.string.roots_remove)) }
        }
    }
}

@Composable
private fun SummaryStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(Modifier.padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.summary_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.summary_accounts), style = MaterialTheme.typography.titleMedium)
            }
        }
        items(state.accounts, key = { it.id.value }) { account ->
            AccountRow(account, onBrowse = { viewModel.browseAccount(account.id) })
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.roots_title), style = MaterialTheme.typography.titleMedium)
                RootsList(state.roots, viewModel)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::addAnotherAccount,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget)
                ) { Text(stringResource(R.string.summary_add_account)) }
                if (!state.canFinish) {
                    Text(
                        stringResource(R.string.summary_need_root),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Button(
                    onClick = viewModel::finish,
                    enabled = state.canFinish,
                    modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget)
                ) { Text(stringResource(R.string.summary_finish)) }
            }
        }
    }
}

@Composable
private fun AccountRow(account: ConnectedAccount, onBrowse: () -> Unit) {
    val label = if (account.role == AccountRole.SHARED) {
        stringResource(R.string.summary_account_shared, account.email)
    } else {
        stringResource(R.string.summary_account_personal, account.email)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onBrowse, modifier = Modifier.heightIn(min = MinTarget)) {
                Text(stringResource(R.string.summary_add_folder, account.email))
            }
        }
    }
}
