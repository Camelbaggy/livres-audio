package io.github.camelbaggy.livresaudio.core.data.repo

import io.github.camelbaggy.livresaudio.core.data.db.LibraryDatabase
import io.github.camelbaggy.livresaudio.core.data.db.SyncStateEntity
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** One `sync_state` row: an opaque Drive [token] (change page token) or just a timestamp ([syncedAtMs]). */
data class SyncMark(val token: String?, val syncedAtMs: Long)

/** Key/value bookkeeping of Drive synchronisations (CLAUDE.md §4.2 `sync_state`). Never holds secrets. */
interface SyncStateRepository {
    suspend fun get(key: String): SyncMark?

    suspend fun put(key: String, token: String?, syncedAtMs: Long)

    suspend fun delete(key: String)

    companion object {
        /** `changes.list` page token of an account. */
        fun changesKey(accountId: String) = "changes:$accountId"

        /** Last COMPLETE listing of a watched root (Drive folder id). */
        fun fullScanKey(rootId: String) = "fullscan:$rootId"
    }
}

/** Which watched roots hold the books anchored on given Drive ids (used for changes that carry no parent). */
interface BookRootLookup {
    suspend fun rootIdsFor(driveIds: Collection<String>): Set<String>
}

class RoomSyncStateRepository @Inject constructor(
    private val db: LibraryDatabase,
    @DataIoDispatcher private val dispatcher: CoroutineDispatcher
) : SyncStateRepository {
    override suspend fun get(key: String): SyncMark? = withContext(dispatcher) {
        db.syncStateDao().get(key)?.let { SyncMark(it.driveFileId, it.lastSyncedAt) }
    }

    override suspend fun put(key: String, token: String?, syncedAtMs: Long) = withContext(dispatcher) {
        db.syncStateDao().upsert(SyncStateEntity(key, token, null, syncedAtMs))
    }

    override suspend fun delete(key: String) = withContext(dispatcher) { db.syncStateDao().delete(key) }
}

class RoomBookRootLookup @Inject constructor(
    private val db: LibraryDatabase,
    @DataIoDispatcher private val dispatcher: CoroutineDispatcher
) : BookRootLookup {
    override suspend fun rootIdsFor(driveIds: Collection<String>): Set<String> = withContext(dispatcher) {
        val out = HashSet<String>()
        driveIds.distinct().chunked(CHUNK).forEach { ids ->
            out += db.bookDao().rootIdsForDriveIds(ids)
            out += db.bookFileDao().rootIdsForDriveFileIds(ids)
        }
        out
    }

    private companion object {
        const val CHUNK = 400
    }
}
