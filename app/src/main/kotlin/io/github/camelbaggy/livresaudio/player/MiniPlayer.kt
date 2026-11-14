package io.github.camelbaggy.livresaudio.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.camelbaggy.livresaudio.R
import io.github.camelbaggy.livresaudio.ui.BookCover
import io.github.camelbaggy.livresaudio.ui.PlayerIcons

/** Persistent mini-player at the bottom of the library, shown while a book is loaded. */
@Composable
fun MiniPlayer(viewModel: PlayerViewModel, onOpenPlayer: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.loaded) return
    val title = state.title ?: stringResource(R.string.library_untitled)
    val open = stringResource(R.string.mini_player_open)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenPlayer)
                    .semantics { contentDescription = open }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BookCover(
                    bookId = state.bookId,
                    title = state.title,
                    coverUri = state.coverUri,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = state.chapterTitle ?: statusLabel(state.status),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = viewModel::togglePlay, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = if (state.isPlaying) PlayerIcons.Pause else PlayerIcons.Play,
                        contentDescription = stringResource(
                            if (state.isPlaying) R.string.player_pause else R.string.player_play
                        )
                    )
                }
            }
            val duration = state.chapterDurationMs
            if (duration != null && duration > 0) {
                LinearProgressIndicator(
                    progress = { (state.chapterPositionMs.toFloat() / duration).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                )
            }
        }
    }
}

@Composable
internal fun statusLabel(status: PlayerStatus): String = stringResource(
    when (status) {
        PlayerStatus.PLAYING -> R.string.player_status_playing
        PlayerStatus.PAUSED -> R.string.player_status_paused
        PlayerStatus.LOADING -> R.string.player_status_loading
        PlayerStatus.WAITING_NETWORK -> R.string.player_status_waiting
        PlayerStatus.NOT_FOUND -> R.string.player_status_not_found
        PlayerStatus.ERROR -> R.string.player_status_error
        PlayerStatus.ENDED -> R.string.player_status_ended
    }
)
