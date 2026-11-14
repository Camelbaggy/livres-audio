package io.github.camelbaggy.livresaudio.e2

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.camelbaggy.livresaudio.R
import io.github.camelbaggy.livresaudio.core.playback.PlaybackStatus
import io.github.camelbaggy.livresaudio.e1.asText
import kotlinx.coroutines.launch

private val ButtonHeight = 48.dp
private val SPEEDS = listOf(1f, 1.5f, 2f)

@Composable
@UnstableApi
fun E2Screen(viewModel: E2ViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    AskNotificationPermission()

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(Modifier.padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = ButtonHeight)) {
                        Text(stringResource(R.string.e2_back))
                    }
                    Text(stringResource(R.string.e2_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.e2_intro), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.accounts.isEmpty()) {
                item { Text(stringResource(R.string.e2_no_account)) }
            } else {
                item { Text(stringResource(R.string.e2_account), style = MaterialTheme.typography.titleMedium) }
                items(state.accounts, key = { it.id.value }) { account ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = ButtonHeight).clickable {
                            viewModel.selectAccount(account.id)
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = account.id == state.selectedAccountId, onClick = null)
                        Text(account.email, Modifier.padding(start = 8.dp))
                    }
                }
                item { SearchSection(state, viewModel) }
                items(state.results, key = { it.id }) { file ->
                    ResultRow(file, selected = file.id == state.current?.id) { viewModel.play(file) }
                }
            }
            state.current?.let { current ->
                item { PlayerCard(current, state, viewModel) }
            }
            item {
                Text(stringResource(R.string.e1_log_title), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("journal E2", state.log.asText()))
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
}

@Composable
private fun AskNotificationPermission() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
@UnstableApi
private fun SearchSection(state: E2UiState, viewModel: E2ViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            label = { Text(stringResource(R.string.e2_search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = viewModel::search,
            enabled = !state.searching,
            modifier = Modifier.fillMaxWidth().heightIn(min = ButtonHeight)
        ) { Text(stringResource(R.string.e2_search)) }
        if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.searchMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!state.searching && state.results.isEmpty() && state.searchMessage == null) {
            Text(stringResource(R.string.e2_no_results), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ResultRow(file: AudioFile, selected: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().heightIn(min = ButtonHeight).clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                file.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                "${formatMegabytes(file.sizeBytes)} · ${file.mimeType ?: "?"}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
@UnstableApi
private fun PlayerCard(file: AudioFile, state: E2UiState, viewModel: E2ViewModel) {
    val player = state.player
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = player.durationMs
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(file.name, style = MaterialTheme.typography.titleSmall)
            Text(statusText(player.status), style = MaterialTheme.typography.titleMedium)
            val shown = dragging ?: player.positionMs.toFloat()
            Slider(
                value = shown.coerceIn(0f, (duration ?: 1L).toFloat()),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { viewModel.seekToMs(it.toLong()) }
                    dragging = null
                },
                valueRange = 0f..(duration ?: 1L).toFloat(),
                enabled = duration != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = ButtonHeight)
            )
            Text(
                stringResource(
                    R.string.e2_position,
                    formatDuration(shown.toLong()),
                    formatDuration(duration)
                )
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = viewModel::seekBack,
                    modifier = Modifier.weight(1f).heightIn(min = ButtonHeight)
                ) { Text(stringResource(R.string.e2_back_10)) }
                Button(
                    onClick = viewModel::togglePlay,
                    modifier = Modifier.weight(1.3f).heightIn(min = ButtonHeight)
                ) {
                    val playing = player.status == PlaybackStatus.Playing
                    Text(stringResource(if (playing) R.string.e2_pause else R.string.e2_play))
                }
                OutlinedButton(
                    onClick = viewModel::seekForward,
                    modifier = Modifier.weight(1f).heightIn(min = ButtonHeight)
                ) { Text(stringResource(R.string.e2_forward_30)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SPEEDS.forEach { speed ->
                    val selected = player.speed == speed
                    val label = stringResource(R.string.e2_speed, speedLabel(speed))
                    if (selected) {
                        Button(
                            onClick = { viewModel.setSpeed(speed) },
                            modifier = Modifier.weight(1f).heightIn(min = ButtonHeight)
                        ) { Text(label) }
                    } else {
                        OutlinedButton(
                            onClick = { viewModel.setSpeed(speed) },
                            modifier = Modifier.weight(1f).heightIn(min = ButtonHeight)
                        ) { Text(label) }
                    }
                }
            }
            Text(
                stringResource(R.string.e2_buffered, formatDuration(player.bufferedPositionMs)),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                stringResource(R.string.e2_ahead, player.aheadMs / 1000),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                stringResource(
                    R.string.e2_cache_file,
                    formatMegabytes(state.cacheFileBytes),
                    formatMegabytes(file.sizeBytes)
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                stringResource(R.string.e2_cache_total, formatMegabytes(state.cacheTotalBytes)),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                stringResource(if (state.online) R.string.e2_network_on else R.string.e2_network_off),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.online) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
            )
            OutlinedButton(
                onClick = viewModel::clearCache,
                modifier = Modifier.fillMaxWidth().heightIn(min = ButtonHeight)
            ) { Text(stringResource(R.string.e2_clear_cache)) }
        }
    }
}

private fun speedLabel(speed: Float): String = if (speed % 1f == 0f) speed.toInt().toString() else "1,5"

@Composable
private fun statusText(status: PlaybackStatus): String = when (status) {
    PlaybackStatus.Playing -> stringResource(R.string.e2_status_playing)
    PlaybackStatus.Paused -> stringResource(R.string.e2_status_paused)
    PlaybackStatus.Loading -> stringResource(R.string.e2_status_loading)
    PlaybackStatus.WaitingForNetwork -> stringResource(R.string.e2_status_waiting)
    PlaybackStatus.Ended -> stringResource(R.string.e2_status_ended)
    is PlaybackStatus.Error -> stringResource(R.string.e2_status_error, status.description)
}
