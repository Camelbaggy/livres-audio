package io.github.camelbaggy.livresaudio.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import io.github.camelbaggy.livresaudio.core.model.ListeningSettings
import io.github.camelbaggy.livresaudio.ui.PlayerIcons
import io.github.camelbaggy.livresaudio.util.formatDuration
import io.github.camelbaggy.livresaudio.util.formatSpeed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningSettingsScreen(
    viewModel: ListeningSettingsViewModel,
    onOpenAccounts: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(PlayerIcons.ArrowBack, stringResource(R.string.book_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onOpenAccounts,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) { Text(stringResource(R.string.settings_accounts)) }
            HorizontalDivider()
            SectionTitle(R.string.settings_rewind_title)
            SwitchRow(
                stringResource(R.string.settings_rewind_enabled),
                settings.rewindEnabled,
                viewModel::setRewindEnabled
            )
            if (settings.rewindEnabled) {
                settings.rewindTable.forEachIndexed { index, step ->
                    val next = settings.rewindTable.getOrNull(index + 1)
                    val rewindSeconds = (step.rewindMs / 1000).toInt()
                    val rewindText = if (rewindSeconds == 0) {
                        stringResource(R.string.settings_rewind_none)
                    } else {
                        stringResource(R.string.settings_rewind_seconds, rewindSeconds)
                    }
                    val label = if (step.minPauseMs == 0L && next != null) {
                        stringResource(R.string.settings_rewind_step_below, formatDuration(next.minPauseMs), rewindText)
                    } else {
                        stringResource(R.string.settings_rewind_step_from, formatDuration(step.minPauseMs), rewindText)
                    }
                    LabeledSlider(
                        label = label,
                        value = rewindSeconds.toFloat(),
                        range = 0f..MAX_REWIND_SECONDS.toFloat(),
                        steps = MAX_REWIND_SECONDS - 1,
                        onChange = { viewModel.setStepRewindSeconds(index, it.toInt()) }
                    )
                }
                OutlinedButton(
                    onClick = viewModel::resetRewindTable,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.settings_rewind_reset)) }
            }
            HorizontalDivider()
            SectionTitle(R.string.settings_skips_title)
            LabeledSlider(
                label = stringResource(R.string.settings_skip_back, (settings.skipBackMs / 1000).toInt()),
                value = settings.skipBackMs / 1000f,
                range = MIN_SKIP_SECONDS.toFloat()..MAX_SKIP_SECONDS.toFloat(),
                steps = SKIP_SLIDER_STEPS,
                onChange = { viewModel.setSkipBackSeconds(it.toInt()) }
            )
            LabeledSlider(
                label = stringResource(R.string.settings_skip_forward, (settings.skipForwardMs / 1000).toInt()),
                value = settings.skipForwardMs / 1000f,
                range = MIN_SKIP_SECONDS.toFloat()..MAX_SKIP_SECONDS.toFloat(),
                steps = SKIP_SLIDER_STEPS,
                onChange = { viewModel.setSkipForwardSeconds(it.toInt()) }
            )
            HorizontalDivider()
            SectionTitle(R.string.settings_speed_title)
            LabeledSlider(
                label = stringResource(R.string.settings_speed_default, formatSpeed(settings.defaultSpeed)),
                value = settings.defaultSpeed,
                range = ListeningSettings.MIN_SPEED..ListeningSettings.MAX_SPEED,
                steps = SPEED_SLIDER_STEPS,
                onChange = viewModel::setDefaultSpeed
            )
            HorizontalDivider()
            SectionTitle(R.string.settings_headset_title)
            SwitchRow(
                stringResource(R.string.settings_headset_chapters),
                settings.headsetButtonsAsChapters,
                viewModel::setHeadsetButtonsAsChapters
            )
        }
    }
}

/** Slider stops: 5..120 s by 5 s, and 0.5..3.0 by 0.05. */
private const val SKIP_SLIDER_STEPS = 22
private const val SPEED_SLIDER_STEPS = 49

@Composable
private fun SectionTitle(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label }
        )
    }
}
