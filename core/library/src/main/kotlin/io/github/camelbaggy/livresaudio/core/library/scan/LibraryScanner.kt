package io.github.camelbaggy.livresaudio.core.library.scan

import io.github.camelbaggy.livresaudio.core.data.db.WatchedRootEntity
import io.github.camelbaggy.livresaudio.core.data.identity.bookIdFor
import io.github.camelbaggy.livresaudio.core.data.repo.AccountRepository
import io.github.camelbaggy.livresaudio.core.data.repo.LibraryRepository
import io.github.camelbaggy.livresaudio.core.data.repo.SyncStateRepository
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveError
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveResult
import io.github.camelbaggy.livresaudio.core.library.GroupingRole
import io.github.camelbaggy.livresaudio.core.library.LibraryBuilder
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.core.model.BookOrigin
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Why a root could not be walked. */
sealed interface ScanFailure {
    data class Drive(val error: DriveError) : ScanFailure

    /** The root belongs to an account that is no longer connected. */
    data object UnknownAccount : ScanFailure

    data class Storage(val message: String) : ScanFailure
}

sealed interface RootScanOutcome {
    /** The whole root was listed; [missing] books were flagged MISSING. */
    data class Done(val found: Int, val added: Int, val missing: Int) : RootScanOutcome

    /** Nothing was flagged MISSING for this root. Books already stored are untouched. */
    data class Failed(val failure: ScanFailure) : RootScanOutcome
}

data class RootScanResult(
    val accountId: String,
    val driveFolderId: String,
    val name: String,
    val outcome: RootScanOutcome
)

data class ScanReport(val roots: List<RootScanResult>, val purged: Int) {
    val hasFailure: Boolean get() = roots.any { it.outcome is RootScanOutcome.Failed }
}

/** AUTO = incremental (`changes.list`) plus whatever is due; FULL = list every root again. */
enum class ScanMode { AUTO, FULL }

/**
 * Walks watched roots and feeds the library (CLAUDE.md §5.2).
 *
 * Per account: the stored `changes` token tells which roots were touched since the last run; only those roots
 * (plus roots never listed or not listed for [FULL_SCAN_PERIOD_DAYS] days, or all of them without a usable
 * token) are listed again, ENTIRELY, with the same code as a full walk. The token is stored only after every
 * targeted root was listed and stored without error; otherwise the same changes are replayed next time
 * (applying them is idempotent). A new token is read BEFORE a full walk so nothing that happens during it is lost.
 *
 * Safety rule: books are flagged MISSING only after a root was listed COMPLETELY and stored without error; any
 * failure leaves the stored books of that root untouched. Walks are serialised by a [Mutex].
 * Must be a singleton (the mutex lives in the instance).
 */
class LibraryScanner(
    private val accounts: AccountRepository,
    private val library: LibraryRepository,
    private val lister: TreeLister,
    private val changes: ChangeFeed,
    private val syncState: SyncStateRepository,
    private val changedRoots: ChangedRootsResolver,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val builder: LibraryBuilder = LibraryBuilder()
) {
    private val mutex = Mutex()

    suspend fun scan(mode: ScanMode = ScanMode.AUTO): ScanReport = mutex.withLock {
        withContext(dispatcher) {
            val roles = accounts.getAccounts().associate { it.id.value to it.role }
            val results = accounts.getWatchedRoots().groupBy { it.accountId }.flatMap { (accountId, roots) ->
                val role = roles[accountId]
                if (role == null) {
                    roots.map { RootScanResult(it.accountId, it.driveFolderId, it.name, unknownAccount) }
                } else {
                    scanAccount(AccountId(accountId), role, roots, mode)
                }
            }
            val purged = purge()
            ScanReport(results, purged)
        }
    }

    private val unknownAccount = RootScanOutcome.Failed(ScanFailure.UnknownAccount)

    private suspend fun scanAccount(
        account: AccountId,
        role: AccountRole,
        roots: List<WatchedRootEntity>,
        mode: ScanMode
    ): List<RootScanResult> {
        fun failAll(failure: ScanFailure) =
            roots.map { RootScanResult(it.accountId, it.driveFolderId, it.name, RootScanOutcome.Failed(failure)) }

        val now = clock()
        val tokenKey = SyncStateRepository.changesKey(account.value)
        val targets = LinkedHashSet<String>()
        var token: String? = if (mode == ScanMode.FULL) null else syncState.get(tokenKey)?.token
        var newToken: String? = null
        try {
            for (root in roots) {
                val last = syncState.get(SyncStateRepository.fullScanKey(root.driveFolderId))?.syncedAtMs
                if (mode == ScanMode.FULL || last == null || now - last >= FULL_SCAN_PERIOD_MS) {
                    targets += root.driveFolderId
                }
            }
            if (token != null) {
                when (val batch = changes.changesSince(account, token)) {
                    is DriveResult.Failure ->
                        if (batch.error is DriveError.InvalidChangeToken) {
                            token = null
                        } else {
                            return failAll(ScanFailure.Drive(batch.error))
                        }

                    is DriveResult.Success -> {
                        newToken = batch.value.newToken
                        val rootIds = roots.map { it.driveFolderId }.toSet()
                        when (val r = changedRoots.resolve(account, batch.value.entries, rootIds)) {
                            is DriveResult.Failure -> return failAll(ScanFailure.Drive(r.error))
                            is DriveResult.Success -> targets += if (r.value.overflow) rootIds else r.value.roots
                        }
                    }
                }
            }
            if (token == null) {
                // No (usable) token: list everything, after taking the token so nothing is missed meanwhile.
                targets += roots.map { it.driveFolderId }
                newToken = (changes.startToken(account) as? DriveResult.Success)?.value
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failAll(ScanFailure.Storage(e.message ?: e::class.java.simpleName))
        }

        val results = roots.filter { it.driveFolderId in targets }.map { root ->
            val outcome = scanRoot(root.accountId, root.driveFolderId, role)
            if (outcome is RootScanOutcome.Done) recordFullScan(root.driveFolderId, now)
            RootScanResult(root.accountId, root.driveFolderId, root.name, outcome)
        }
        if (newToken != null && results.none { it.outcome is RootScanOutcome.Failed }) {
            try {
                syncState.put(tokenKey, newToken, now)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Token not stored: the same changes are replayed next time.
            }
        }
        return results
    }

    private suspend fun recordFullScan(rootId: String, now: Long) {
        try {
            syncState.put(SyncStateRepository.fullScanKey(rootId), null, now)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Worst case: the root is listed again at the next run.
        }
    }

    private suspend fun scanRoot(accountId: String, rootId: String, role: AccountRole): RootScanOutcome = try {
        val account = AccountId(accountId)
        when (val listing = lister.list(account, rootId)) {
            is DriveResult.Failure -> RootScanOutcome.Failed(ScanFailure.Drive(listing.error))

            is DriveResult.Success -> {
                val grouping = if (role == AccountRole.SHARED) GroupingRole.SERIES else GroupingRole.AUTO
                val books = builder.build(rootId, listing.value, grouping)
                val now = clock()
                val origin = BookOrigin.from(role)
                val seen = HashSet<String>()
                var added = 0
                for (book in books) {
                    if (library.getBook(bookIdFor(book.anchor)) == null) added++
                    seen += library.upsertDiscoveredBook(book, account, origin, now)
                }
                val missing = library.markMissingNotSeen(rootId, seen, now)
                RootScanOutcome.Done(found = seen.size, added = added, missing = missing)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RootScanOutcome.Failed(ScanFailure.Storage(e.message ?: e::class.java.simpleName))
    }

    private suspend fun purge(): Int = try {
        library.purgeMissingBefore(clock() - TimeUnit.DAYS.toMillis(MISSING_RETENTION_DAYS))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        0
    }

    companion object {
        const val MISSING_RETENTION_DAYS = 90L

        /** Safety net: every root is listed completely at least this often, whatever `changes` said. */
        const val FULL_SCAN_PERIOD_DAYS = 7L
        val FULL_SCAN_PERIOD_MS = TimeUnit.DAYS.toMillis(FULL_SCAN_PERIOD_DAYS)
    }
}
