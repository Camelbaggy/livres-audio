package io.github.camelbaggy.livresaudio.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.camelbaggy.livresaudio.R
import io.github.camelbaggy.livresaudio.core.model.ListeningSettings
import io.github.camelbaggy.livresaudio.settings.snapSpeed
import io.github.camelbaggy.livresaudio.ui.BookCover
import io.github.camelbaggy.livresaudio.ui.PlayerIcons
import io.github.camelbaggy.livresaudio.util.formatClock
import io.github.camelbaggy.livresaudio.util.formatDuration
import io.github.camelbaggy.livresaudio.util.formatSpeed

private val SPEED_SHORTCUTS = listOf(1.0f, 1.25f, 1.5f, 2.0f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(viewModel: PlayerViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showChapters by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.player_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(PlayerIcons.ArrowBack, stringResource(R.string.book_back))
                    }
                }
            )
        }
    ) { padding ->
        if (!state.loaded) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.player_nothing), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
                // Top half: cover, titles, chapter progress.
                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        BookCover(
                            bookId = state.bookId,
                            title = state.title,
                            coverUri = state.coverUri,
                            modifier = Modifier.aspectRatio(1f).fillMaxSize().clip(RoundedCornerShape(16.dp))
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.chapterTitle ?: state.title.orEmpty(),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOfNotNull(
                            state.title.takeIf { state.chapterTitle != null },
                            state.author
                        ).joinToString(" — "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    ChapterSeekBar(state = state, onSeek = viewModel::seekToChapterFraction)
                    Text(
                        text = listOfNotNull(
                            statusLabel(state.status),
                            state.remainingBookMs?.let { stringResource(R.string.player_remaining, formatDuration(it)) }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Bottom half: large controls, reachable with one hand.
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TransportRow(state = state, viewModel = viewModel)
                    SecondaryRow(
                        speed = state.speed,
                        onSpeed = { showSpeed = true },
                        onChapters = { showChapters = true }
                    )
                }
            }
        }
    }

    if (showChapters) {
        ChaptersSheet(
            state = state,
            onSelect = {
                viewModel.seekToChapter(it)
                showChapters = false
            },
            onDismiss = { showChapters = false }
        )
    }
    if (showSpeed) {
        SpeedSheet(speed = state.speed, onSpeed = viewModel::setSpeed, onDismiss = { showSpeed = false })
    }
}

@Composable
private fun ChapterSeekBar(state: PlayerUiState, onSeek: (Float) -> Unit) {
    val duration = state.chapterDurationMs
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = if (duration != null && duration > 0) {
        (state.chapterPositionMs.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }
    val label = stringResource(R.string.player_chapter_slider)
    Slider(
        value = dragging ?: fraction,
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let(onSeek)
            dragging = null
        },
        enabled = duration != null && duration > 0,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }
    )
    val shownPosition = if (dragging != null &&
        duration != null
    ) {
        (duration * dragging!!).toLong()
    } else {
        state.chapterPositionMs
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatClock(shownPosition), style = MaterialTheme.typography.bodySmall)
        Text(duration?.let { formatClock(it) }.orEmpty(), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TransportRow(state: PlayerUiState, viewModel: PlayerViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TransportButton(PlayerIcons.SkipPrevious, stringResource(R.string.player_previous_chapter), 56.dp) {
            viewModel.previousChapter()
        }
        TransportButton(
            PlayerIcons.Replay,
            stringResource(R.string.player_skip_back, (state.skipBackMs / 1000).toInt()),
            64.dp,
            caption = "${state.skipBackMs / 1000}"
        ) { viewModel.skipBack() }
        FilledIconButton(onClick = viewModel::togglePlay, modifier = Modifier.size(84.dp)) {
            Icon(
                imageVector = if (state.isPlaying) PlayerIcons.Pause else PlayerIcons.Play,
                contentDescription = stringResource(
                    if (state.isPlaying) R.string.player_pause else R.string.player_play
                ),
                modifier = Modifier.size(44.dp)
            )
        }
        TransportButton(
            PlayerIcons.Forward,
            stringResource(R.string.player_skip_forward, (state.skipForwardMs / 1000).toInt()),
            64.dp,
            caption = "${state.skipForwardMs / 1000}"
        ) { viewModel.skipForward() }
        TransportButton(PlayerIcons.SkipNext, stringResource(R.string.player_next_chapter), 56.dp) {
            viewModel.nextChapter()
        }
    }
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp,
    caption: String? = null,
    onClick: () -> Unit
) {
    Box(contentAlignment = Alignment.Center) {
        IconButton(onClick = onClick, modifier = Modifier.size(size)) {
            Icon(icon, description, modifier = Modifier.size(size * 0.6f))
        }
        if (caption != null) {
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun SecondaryRow(speed: Float, onSpeed: () -> Unit, onChapters: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val speedText = stringResource(R.string.player_speed_value, formatSpeed(speed))
        TextButton(
            onClick = onSpeed,
            modifier = Modifier.height(48.dp).semantics { contentDescription = speedText }
        ) { Text(speedText, style = MaterialTheme.typography.titleMedium) }
        LabeledIconButton(
            PlayerIcons.List,
            stringResource(R.string.player_chapters),
            enabled = true,
            onClick = onChapters
        )
        LabeledIconButton(
            PlayerIcons.Timer,
            stringResource(R.string.player_timer),
            enabled = false,
            description = stringResource(R.string.player_timer_soon)
        )
        LabeledIconButton(
            PlayerIcons.Bookmark,
            stringResource(R.string.player_bookmark),
            enabled = false,
            description = stringResource(R.string.player_bookmark_soon)
        )
    }
}

@Composable
private fun LabeledIconButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    description: String = label,
    onClick: () -> Unit = {}
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(icon, description)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChaptersSheet(state: PlayerUiState, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.player_chapters),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyColumn {
            itemsIndexed(state.chapters) { index, chapter ->
                val current = index == state.chapterIndex
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp).clickable {
                        onSelect(index)
                    }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = chapter.title ?: stringResource(R.string.chapter_default, index + 1),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    chapter.durationMs?.let {
                        Text(
                            formatClock(it),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSheet(speed: Float, onSpeed: (Float) -> Unit, onDismiss: () -> Unit) {
    var local by remember { mutableStateOf(speed) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.player_speed, formatSpeed(local)),
                style = MaterialTheme.typography.titleLarge
            )
            Slider(
                value = local,
                onValueChange = {
                    local = snapSpeed(it)
                    onSpeed(local)
                },
                valueRange = ListeningSettings.MIN_SPEED..ListeningSettings.MAX_SPEED,
                steps = SPEED_STEPS,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SPEED_SHORTCUTS.forEach { shortcut ->
                    FilterChip(
                        selected = local == shortcut,
                        onClick = {
                            local = shortcut
                            onSpeed(shortcut)
                        },
                        label = { Text(stringResource(R.string.player_speed_value, formatSpeed(shortcut))) },
                        modifier = Modifier.height(48.dp)
                    )
                }
            }
        }
    }
}

/** Intermediate stops of the 0.5..3.0 slider so that it moves by 0.05. */
private const val SPEED_STEPS = 49
