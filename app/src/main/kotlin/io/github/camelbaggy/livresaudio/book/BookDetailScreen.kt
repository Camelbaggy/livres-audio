package io.github.camelbaggy.livresaudio.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.camelbaggy.livresaudio.R
import io.github.camelbaggy.livresaudio.core.model.ListeningStatus
import io.github.camelbaggy.livresaudio.ui.BookCover
import io.github.camelbaggy.livresaudio.ui.PlayerIcons
import io.github.camelbaggy.livresaudio.ui.rememberPlaybackGate
import io.github.camelbaggy.livresaudio.util.formatClock
import io.github.camelbaggy.livresaudio.util.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    viewModel: BookDetailViewModel,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gate = rememberPlaybackGate()

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(PlayerIcons.ArrowBack, stringResource(R.string.book_back))
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.loading -> Box(Modifier.padding(padding).fillMaxSize())

            state.notFound -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.book_not_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            else -> LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Header(state) }
                item {
                    Actions(
                        state = state,
                        onPlay = {
                            gate {
                                viewModel.play()
                                onOpenPlayer()
                            }
                        },
                        onFinish = viewModel::markFinished,
                        onReset = viewModel::requestReset
                    )
                }
                state.description?.takeIf { it.isNotBlank() }?.let { description ->
                    item {
                        Text(stringResource(R.string.book_description), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(description, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (state.chapters.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.book_chapters),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    itemsIndexed(state.chapters) { index, chapter ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                chapter.title ?: stringResource(R.string.chapter_default, index + 1),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            chapter.durationMs?.let {
                                Text(
                                    formatClock(it),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.confirmReset) {
        AlertDialog(
            onDismissRequest = viewModel::dismissReset,
            title = { Text(stringResource(R.string.book_reset_title)) },
            text = { Text(stringResource(R.string.book_reset_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmReset) { Text(stringResource(R.string.book_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissReset) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun Header(state: BookDetailState) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BookCover(
            bookId = state.bookId,
            title = state.title,
            coverUri = state.coverUri,
            modifier = Modifier.size(220.dp).clip(RoundedCornerShape(12.dp))
        )
        Spacer(Modifier.height(12.dp))
        Text(
            state.title ?: stringResource(R.string.library_untitled),
            style = MaterialTheme.typography.headlineSmall
        )
        state.author?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        state.narrator?.let {
            Text(stringResource(R.string.book_narrated_by, it), style = MaterialTheme.typography.bodyMedium)
        }
        state.seriesName?.let { name ->
            val index = state.seriesIndex?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
            Text(
                if (index != null) stringResource(R.string.book_series, name, index) else name,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        state.totalDurationMs?.let {
            Text(
                stringResource(R.string.book_total_duration, formatDuration(it)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Actions(state: BookDetailState, onPlay: () -> Unit, onFinish: () -> Unit, onReset: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val progressText = when (state.status) {
            ListeningStatus.FINISHED -> stringResource(R.string.book_finished)

            ListeningStatus.IN_PROGRESS ->
                stringResource(
                    R.string.book_progress,
                    state.progress?.let { "${(it * 100).toInt()} %" } ?: formatDuration(state.bookPositionMs)
                )

            else -> stringResource(R.string.book_not_started)
        }
        Text(progressText, style = MaterialTheme.typography.bodyMedium)
        state.progress?.let {
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().height(4.dp))
        }
        Button(onClick = onPlay, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(
                stringResource(
                    if (state.canResume ||
                        state.loadedInPlayer
                    ) {
                        R.string.book_resume
                    } else {
                        R.string.book_play
                    }
                )
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onFinish, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.book_mark_finished))
            }
            OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.book_reset))
            }
        }
    }
}
