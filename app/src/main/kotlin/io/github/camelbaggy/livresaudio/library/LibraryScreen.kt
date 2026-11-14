package io.github.camelbaggy.livresaudio.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.camelbaggy.livresaudio.R
import io.github.camelbaggy.livresaudio.core.model.BookOrigin
import io.github.camelbaggy.livresaudio.player.MiniPlayer
import io.github.camelbaggy.livresaudio.player.PlayerViewModel
import io.github.camelbaggy.livresaudio.ui.BookCover
import io.github.camelbaggy.livresaudio.ui.PlayerIcons
import io.github.camelbaggy.livresaudio.ui.rememberPlaybackGate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    onOpenBook: (String) -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditSetup: () -> Unit,
    onOpenDiagnostic: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    val gate = rememberPlaybackGate()

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (searching) {
                        IconButton(onClick = {
                            searching = false
                            viewModel.onQueryChange("")
                        }, modifier = Modifier.size(48.dp)) {
                            Icon(PlayerIcons.ArrowBack, stringResource(R.string.library_search_close))
                        }
                    }
                },
                title = {
                    if (searching) {
                        TextField(
                            value = state.query,
                            onValueChange = viewModel::onQueryChange,
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.library_search_hint)) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(stringResource(R.string.library_title))
                    }
                },
                actions = {
                    if (searching) {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onQueryChange("") }, modifier = Modifier.size(48.dp)) {
                                Icon(PlayerIcons.Close, stringResource(R.string.library_search_clear))
                            }
                        }
                    } else {
                        IconButton(onClick = { searching = true }, modifier = Modifier.size(48.dp)) {
                            Icon(PlayerIcons.Search, stringResource(R.string.library_search))
                        }
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }, modifier = Modifier.size(48.dp)) {
                            Icon(PlayerIcons.Sort, stringResource(R.string.library_sort))
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            LibrarySort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(option.labelRes())) },
                                    leadingIcon = { RadioButton(selected = state.sort == option, onClick = null) },
                                    onClick = {
                                        viewModel.onSortChange(option)
                                        sortMenu = false
                                    }
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { overflowMenu = true }, modifier = Modifier.size(48.dp)) {
                            Icon(PlayerIcons.MoreVert, stringResource(R.string.library_more))
                        }
                        DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_settings)) },
                                onClick = {
                                    overflowMenu = false
                                    onOpenSettings()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_edit_setup)) },
                                onClick = {
                                    overflowMenu = false
                                    onEditSetup()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_diagnostic)) },
                                onClick = {
                                    overflowMenu = false
                                    onOpenDiagnostic()
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = { MiniPlayer(viewModel = playerViewModel, onOpenPlayer = onOpenPlayer) }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            when {
                !state.loaded -> Box(Modifier.fillMaxSize())

                state.libraryEmpty -> EmptyMessage(stringResource(R.string.library_empty))

                state.items.isEmpty() -> EmptyMessage(stringResource(R.string.library_no_result, state.query))

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    state.resume?.let { resume ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "resume") {
                            ResumeBanner(
                                item = resume,
                                onOpen = { onOpenBook(resume.bookId) },
                                onPlay = {
                                    gate {
                                        viewModel.resume(resume.bookId)
                                        onOpenPlayer()
                                    }
                                }
                            )
                        }
                    }
                    items(state.items, key = { it.bookId }) { item ->
                        BookGridItem(item = item, onClick = { onOpenBook(item.bookId) })
                    }
                }
            }
        }
    }
}

private fun LibrarySort.labelRes(): Int = when (this) {
    LibrarySort.TITLE -> R.string.sort_title
    LibrarySort.AUTHOR -> R.string.sort_author
    LibrarySort.ADDED -> R.string.sort_added
    LibrarySort.LAST_PLAYED -> R.string.sort_last_played
}

@Composable
private fun EmptyMessage(text: String) {
    // Scrollable so that pull-to-refresh also works on the empty state.
    Box(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResumeBanner(item: LibraryItem, onOpen: () -> Unit, onPlay: () -> Unit) {
    val title = item.title ?: stringResource(R.string.library_untitled)
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BookCover(
                bookId = item.bookId,
                title = item.title,
                coverUri = item.coverUri,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.library_resume),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                item.author?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                item.progress?.let {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().height(3.dp))
                }
            }
            FilledIconButton(onClick = onPlay, modifier = Modifier.size(56.dp)) {
                Icon(PlayerIcons.Play, stringResource(R.string.library_resume_play, title))
            }
        }
    }
}

@Composable
private fun BookGridItem(item: LibraryItem, onClick: () -> Unit) {
    val title = item.title ?: stringResource(R.string.library_untitled)
    val description = stringResource(R.string.book_item_description, title, item.author.orEmpty())
    Column(
        modifier = Modifier.clickable(onClick = onClick).semantics(mergeDescendants = true) {
            contentDescription = description
        }
    ) {
        Box {
            BookCover(
                bookId = item.bookId,
                title = item.title,
                coverUri = item.coverUri,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))
            )
            OriginBadge(item.origin, Modifier.align(Alignment.TopStart).padding(6.dp))
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { item.progress ?: 0f },
            modifier = Modifier.fillMaxWidth().height(3.dp).clearAndSetSemantics {}
        )
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        item.author?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun OriginBadge(origin: BookOrigin, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
    ) {
        Text(
            text = stringResource(
                if (origin ==
                    BookOrigin.SHARED
                ) {
                    R.string.origin_shared
                } else {
                    R.string.origin_personal
                }
            ),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
