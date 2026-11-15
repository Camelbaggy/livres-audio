package io.github.camelbaggy.livresaudio.library

import io.github.camelbaggy.livresaudio.core.data.db.AnchorKind
import io.github.camelbaggy.livresaudio.core.data.db.BookEntity
import io.github.camelbaggy.livresaudio.core.data.db.BookFileEntity
import io.github.camelbaggy.livresaudio.core.data.db.BookWithFiles
import io.github.camelbaggy.livresaudio.core.data.db.BookWithProgress
import io.github.camelbaggy.livresaudio.core.data.db.ProgressEntity
import io.github.camelbaggy.livresaudio.core.data.repo.LibraryRepository
import io.github.camelbaggy.livresaudio.core.data.repo.ProgressRepository
import io.github.camelbaggy.livresaudio.core.data.repo.SaveResult
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.BookOrigin
import io.github.camelbaggy.livresaudio.core.model.BookState
import io.github.camelbaggy.livresaudio.core.model.DiscoveredBook
import io.github.camelbaggy.livresaudio.core.model.ListeningSettings
import io.github.camelbaggy.livresaudio.core.model.ListeningSettingsStore
import io.github.camelbaggy.livresaudio.core.model.ListeningStatus
import io.github.camelbaggy.livresaudio.core.model.MatchState
import io.github.camelbaggy.livresaudio.core.playback.ActiveBook
import io.github.camelbaggy.livresaudio.player.ActiveBookSource
import io.github.camelbaggy.livresaudio.player.ControllerState
import io.github.camelbaggy.livresaudio.player.PlaybackCommands
import io.github.camelbaggy.livresaudio.player.PlaybackGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

fun bookEntity(
    id: String,
    title: String? = id,
    author: String? = null,
    narrator: String? = null,
    series: String? = null,
    state: BookState = BookState.PRESENT,
    addedAt: Long = 0,
    totalDurationMs: Long? = null,
    origin: BookOrigin = BookOrigin.PERSONAL
) = BookEntity(
    bookId = id,
    accountId = "acc",
    rootId = "root",
    driveFolderId = "f-$id",
    anchorKind = AnchorKind.FOLDER,
    anchorDriveId = "f-$id",
    origin = origin,
    fingerprint = "",
    state = state,
    missingSince = null,
    addedAt = addedAt,
    totalDurationMs = totalDurationMs,
    totalBytes = 0,
    title = title,
    author = author,
    narrator = narrator,
    seriesName = series,
    seriesIndex = null,
    description = null,
    year = null,
    coverUri = null,
    fieldSources = emptyMap(),
    matchState = MatchState.UNMATCHED,
    providerIds = emptyMap()
)

fun progressEntity(
    id: String,
    status: ListeningStatus = ListeningStatus.IN_PROGRESS,
    lastPlayedAt: Long = 1,
    positionMs: Long = 1_000,
    bookPositionMs: Long = positionMs
) = ProgressEntity(
    bookId = id,
    driveFileId = "file-$id",
    fileMd5 = null,
    fileIndex = 0,
    positionMs = positionMs,
    bookPositionMs = bookPositionMs,
    status = status,
    speed = null,
    lastPlayedAt = lastPlayedAt,
    updatedAt = lastPlayedAt,
    dirty = false
)

fun row(book: BookEntity, progress: ProgressEntity? = null) = BookWithProgress(book, progress)

fun bookFile(bookId: String, index: Int, durationMs: Long? = 60_000, probed: Boolean = true) = BookFileEntity(
    id = index + 1L,
    bookId = bookId,
    driveFileId = "$bookId-file$index",
    name = "Fichier $index",
    mimeType = "audio/mpeg",
    sizeBytes = 1,
    md5 = "md5-$index",
    sortIndex = index,
    durationMs = durationMs,
    trackNumber = null,
    discNumber = null,
    probed = probed,
    tagsProbed = probed
)

class FakeLibraryRepository : LibraryRepository {
    val rows = MutableStateFlow<List<BookWithProgress>>(emptyList())
    val books = MutableStateFlow<Map<String, BookWithFiles>>(emptyMap())

    override fun observeLibrary(): Flow<List<BookWithProgress>> = rows

    override fun observeBook(bookId: String): Flow<BookWithFiles?> = books.map { it[bookId] }

    override suspend fun getBook(bookId: String): BookWithFiles? = books.value[bookId]

    override suspend fun upsertDiscoveredBook(
        discovered: DiscoveredBook,
        accountId: AccountId,
        origin: BookOrigin,
        nowMs: Long
    ): String = error("non utilisé")

    override suspend fun markMissingNotSeen(rootId: String, seenBookIds: Set<String>, nowMs: Long): Int = 0

    override suspend fun purgeMissingBefore(cutoffMs: Long): Int = 0
}

class FakeProgressRepository : ProgressRepository {
    val saved = mutableListOf<Saved>()
    val current = MutableStateFlow<ProgressEntity?>(null)

    data class Saved(val bookId: String, val positionMs: Long, val status: ListeningStatus, val allowReset: Boolean)

    override fun observe(bookId: String): Flow<ProgressEntity?> = current

    override suspend fun get(bookId: String): ProgressEntity? = current.value

    override suspend fun savePosition(
        bookId: String,
        driveFileId: String,
        fileMd5: String?,
        fileIndex: Int,
        positionMs: Long,
        bookPositionMs: Long,
        status: ListeningStatus,
        nowMs: Long,
        allowReset: Boolean
    ): SaveResult {
        saved += Saved(bookId, positionMs, status, allowReset)
        return SaveResult.Saved
    }

    override suspend fun setSpeedOverride(bookId: String, speed: Float?, nowMs: Long) = Unit
}

class FakeGateway :
    PlaybackGateway,
    PlaybackCommands {
    override val state = MutableStateFlow(ControllerState())
    val calls = mutableListOf<String>()

    override fun playBook(bookId: String) {
        calls += "playBook:$bookId"
    }

    override fun play() {
        calls += "play"
    }

    override fun pause() {
        calls += "pause"
    }

    override fun seekTo(fileIndex: Int, positionMs: Long) {
        calls += "seekTo:$fileIndex:$positionMs"
    }

    override fun seekBy(deltaMs: Long) {
        calls += "seekBy:$deltaMs"
    }

    override fun resetPosition() {
        calls += "reset"
    }

    override fun setSpeed(speed: Float) {
        calls += "speed:$speed"
    }

    override fun clearSpeedOverride() {
        calls += "clearSpeed"
    }
}

class FakeActiveBookSource(initial: ActiveBook? = null) : ActiveBookSource {
    override val active: MutableStateFlow<ActiveBook?> = MutableStateFlow(initial)
}

class FakeSettingsStore(initial: ListeningSettings = ListeningSettings()) : ListeningSettingsStore {
    val flow = MutableStateFlow(initial)
    override val settings: StateFlow<ListeningSettings> = flow

    override suspend fun update(transform: (ListeningSettings) -> ListeningSettings) {
        flow.value = transform(flow.value)
    }
}
