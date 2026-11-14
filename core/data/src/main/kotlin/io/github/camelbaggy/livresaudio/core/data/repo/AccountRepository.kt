package io.github.camelbaggy.livresaudio.core.data.repo

import androidx.room.withTransaction
import io.github.camelbaggy.livresaudio.core.data.db.AccountEntity
import io.github.camelbaggy.livresaudio.core.data.db.LibraryDatabase
import io.github.camelbaggy.livresaudio.core.data.db.WatchedRootEntity
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.BookState
import io.github.camelbaggy.livresaudio.core.model.ConnectedAccount
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Connected accounts and their watched roots (CLAUDE.md §4.1, §4.2). */
interface AccountRepository {
    fun observeAccounts(): Flow<List<ConnectedAccount>>

    suspend fun getAccounts(): List<ConnectedAccount>

    /** Inserts or updates an account (keeps its `addedAt`). If [ConnectedAccount.isProfile], it becomes the only profile. */
    suspend fun upsertAccount(account: ConnectedAccount, nowMs: Long)

    /**
     * Removes the account and its watched roots (cascade), and flags its PRESENT books MISSING (hidden, data kept
     * 90 days, CLAUDE.md §5.2) in the same transaction, since the scanner no longer walks them. Books are never
     * deleted; progress and bookmarks are never touched.
     */
    suspend fun removeAccount(id: AccountId, nowMs: Long)

    /** Designates [id] as the single profile account (CLAUDE.md §4.1). */
    suspend fun setProfileAccount(id: AccountId)

    fun observeWatchedRoots(): Flow<List<WatchedRootEntity>>

    suspend fun getWatchedRoots(): List<WatchedRootEntity>

    /** Adds a root; no-op if the account already watches this folder. */
    suspend fun addWatchedRoot(accountId: AccountId, driveFolderId: String, name: String, nowMs: Long)

    /** Removes a root and flags the PRESENT books found under it MISSING (same guarantees as [removeAccount]). */
    suspend fun removeWatchedRoot(rootId: Long, nowMs: Long)
}

class RoomAccountRepository @Inject constructor(
    private val db: LibraryDatabase,
    @DataIoDispatcher private val dispatcher: CoroutineDispatcher
) : AccountRepository {
    private val accounts get() = db.accountDao()
    private val roots get() = db.watchedRootDao()

    override fun observeAccounts(): Flow<List<ConnectedAccount>> =
        accounts.observeAll().map { list -> list.map { it.toModel() } }

    override suspend fun getAccounts(): List<ConnectedAccount> =
        withContext(dispatcher) { accounts.getAll().map { it.toModel() } }

    override suspend fun upsertAccount(account: ConnectedAccount, nowMs: Long) = withContext(dispatcher) {
        db.withTransaction {
            val existing = accounts.get(account.id.value)
            accounts.upsert(
                AccountEntity(
                    id = account.id.value,
                    email = account.email,
                    displayName = account.displayName,
                    role = account.role,
                    isProfile = account.isProfile,
                    addedAt = existing?.addedAt ?: nowMs
                )
            )
            if (account.isProfile) accounts.setOnlyProfile(account.id.value)
        }
    }

    override suspend fun removeAccount(id: AccountId, nowMs: Long) = withContext(dispatcher) {
        db.withTransaction {
            val gone = db.bookDao().getByAccount(id.value).filter { it.state == BookState.PRESENT }.map { it.bookId }
            gone.chunked(SQL_CHUNK).forEach { db.bookDao().setState(it, BookState.MISSING, nowMs) }
            accounts.delete(id.value)
        }
    }

    override suspend fun setProfileAccount(id: AccountId) = withContext(dispatcher) {
        accounts.setOnlyProfile(id.value)
    }

    override fun observeWatchedRoots(): Flow<List<WatchedRootEntity>> = roots.observeAll()

    override suspend fun getWatchedRoots(): List<WatchedRootEntity> = withContext(dispatcher) { roots.getAll() }

    override suspend fun addWatchedRoot(accountId: AccountId, driveFolderId: String, name: String, nowMs: Long) =
        withContext(dispatcher) {
            roots.insert(
                WatchedRootEntity(
                    accountId = accountId.value,
                    driveFolderId = driveFolderId,
                    name = name,
                    addedAt = nowMs
                )
            )
            Unit
        }

    override suspend fun removeWatchedRoot(rootId: Long, nowMs: Long) = withContext(dispatcher) {
        db.withTransaction {
            val root = roots.getAll().firstOrNull { it.id == rootId }
            if (root != null) {
                val gone = db.bookDao().getByRoot(root.driveFolderId)
                    .filter { it.state == BookState.PRESENT && it.accountId == root.accountId }
                    .map { it.bookId }
                gone.chunked(SQL_CHUNK).forEach { db.bookDao().setState(it, BookState.MISSING, nowMs) }
            }
            roots.delete(rootId)
        }
    }

    private companion object {
        /** Stays under SQLite's bound-variable limit. */
        const val SQL_CHUNK = 500
    }

    private fun AccountEntity.toModel() = ConnectedAccount(AccountId(id), email, displayName, role, isProfile)
}
