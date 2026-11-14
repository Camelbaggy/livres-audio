package io.github.camelbaggy.livresaudio.core.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import io.github.camelbaggy.livresaudio.core.data.repo.ProgressRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The playback service (CLAUDE.md §5.3): owns the player, the media session, the Media3 notification and the
 * [PositionKeeper] of the loaded book. A controller (the UI, Bluetooth, Android Auto) plays a book by sending a
 * `MediaItem` whose `mediaId` is the `bookId`; the service replaces it with the book's playlist, starting at the
 * position saved in Room.
 *
 * Declaration needed in the `app` manifest: see the report of task 1.6 / `docs` (service with
 * `foregroundServiceType="mediaPlayback"` and the `MediaLibraryService` intent filter).
 */
@UnstableApi
@AndroidEntryPoint
class LivresAudioPlaybackService : MediaLibraryService() {
    @Inject
    lateinit var engine: PlaybackEngine

    @Inject
    lateinit var loader: BookLoader

    @Inject
    lateinit var activeBook: ActiveBookHolder

    @Inject
    @PlaybackMainDispatcher
    lateinit var mainDispatcher: CoroutineDispatcher

    @Inject
    @PlaybackWriteDispatcher
    lateinit var writeDispatcher: CoroutineDispatcher

    @Inject
    lateinit var progress: ProgressRepository

    private lateinit var scope: CoroutineScope
    private var exoPlayer: ExoPlayer? = null
    private var session: MediaLibrarySession? = null
    private var keeper: PositionKeeper? = null

    @Volatile
    private var timeline: ChapterTimeline? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            keeper?.onIsPlayingChanged(isPlaying)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            keeper?.onPlayWhenReadyChanged(playWhenReady)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            keeper?.onPlaybackStateChanged()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            keeper?.onPositionDiscontinuity(discontinuityKindOf(reason))
        }

        override fun onPlayerError(error: PlaybackException) {
            keeper?.onPlayerError()
        }
    }

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        val player = engine.createPlayer()
        player.addListener(playerListener)
        exoPlayer = player
        val chapterPlayer = ChapterPlayer(player) { timeline }
        session = MediaLibrarySession.Builder(this, chapterPlayer, LibraryCallback())
            .setSessionActivity(launchIntent())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** The app was swiped away: save the position now, then let Media3 stop the service unless it is playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        keeper?.flushBlocking()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Before anything is released: the keeper reads the player.
        keeper?.flushBlocking(close = true)
        keeper = null
        scope.cancel()
        session?.release()
        session = null
        exoPlayer?.run {
            removeListener(playerListener)
            release()
        }
        exoPlayer = null
        timeline = null
        activeBook.set(null)
        super.onDestroy()
    }

    private fun launchIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * Loads [bookId], closes the previous book's keeper (final write, awaited so that restoring the same book
     * reads what was just written) and installs the new keeper. Runs on the main dispatcher.
     */
    private suspend fun startBook(bookId: String): MediaSession.MediaItemsWithStartPosition {
        val loaded = loader.load(bookId) ?: throw IllegalStateException("Livre introuvable : $bookId")
        val player = checkNotNull(exoPlayer) { "Service arrêté" }
        keeper?.finish()
        keeper = null
        val newKeeper = PositionKeeper(
            book = loaded.book,
            timeline = loaded.timeline,
            initialTarget = loaded.target,
            repository = progress,
            scope = scope,
            writeDispatcher = writeDispatcher,
            snapshot = { snapshotOf(player) },
            clock = System::currentTimeMillis
        )
        keeper = newKeeper
        timeline = loaded.timeline
        activeBook.set(ActiveBook(loaded.book, loaded.timeline))
        return MediaSession.MediaItemsWithStartPosition(loaded.items, loaded.target.fileIndex, loaded.target.positionMs)
    }

    private fun resetPosition() {
        val player = exoPlayer ?: return
        keeper?.resetToStart()
        player.seekTo(0, 0L)
    }

    private fun <T> futureOf(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        scope.launch {
            try {
                future.set(block())
            } catch (cancelled: CancellationException) {
                future.cancel(false)
                throw cancelled
            } catch (failure: Exception) {
                future.setException(failure)
            }
        }
        return future
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(RESET_POSITION_COMMAND)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == COMMAND_RESET_POSITION) {
                resetPosition()
                return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_SUCCESS)
                )
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            LibraryResult.ofItem(LibraryTree.root(getString(R.string.playback_library_root)), params)
        )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = futureOf {
            val children = when (parentId) {
                LibraryTree.ROOT_ID -> listOf(LibraryTree.inProgress(getString(R.string.playback_in_progress)))
                LibraryTree.IN_PROGRESS_ID -> loader.inProgress().map { LibraryTree.book(it.book) }
                else -> emptyList()
            }
            val from = (page.coerceAtLeast(0).toLong() * pageSize.coerceAtLeast(1)).coerceAtMost(children.size.toLong())
            LibraryResult.ofItemList(ImmutableList.copyOf(children.drop(from.toInt()).take(pageSize)), params)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val single = mediaItems.singleOrNull()
            if (single == null || BookPlaylist.isFileMediaId(single.mediaId)) {
                return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            }
            return futureOf { startBook(single.mediaId) }
        }

        /** Media button / Bluetooth "play" while the service is not running: resume the last book. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = futureOf {
            val last = loader.inProgress().firstOrNull() ?: throw IllegalStateException("Aucun livre en cours")
            startBook(last.book.bookId)
        }
    }

    companion object {
        /** Custom session command: "reset to zero" of the loaded book (explicit user action only). */
        const val COMMAND_RESET_POSITION = "io.github.camelbaggy.livresaudio.RESET_POSITION"
        private val RESET_POSITION_COMMAND = SessionCommand(COMMAND_RESET_POSITION, Bundle.EMPTY)
    }
}
