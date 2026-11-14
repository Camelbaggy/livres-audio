package io.github.camelbaggy.livresaudio.core.library.scan

import io.github.camelbaggy.livresaudio.core.data.identity.bookIdFor
import io.github.camelbaggy.livresaudio.core.data.repo.AccountRepository
import io.github.camelbaggy.livresaudio.core.data.repo.LibraryRepository
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

/**
 * Walks every watched root of every account and feeds the library (CLAUDE.md §5.2).
 *
 * Safety rule: books are flagged MISSING only after a root was listed COMPLETELY and stored without error; any
 * failure leaves the stored books of that root untouched. Walks are serialised by a [Mutex].
 * Must be a singleton (the mutex lives in the instance).
 */
class LibraryScanner(
    private val accounts: AccountRepository,
    private val library: LibraryRepository,
    private val lister: TreeLister,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val builder: LibraryBuilder = LibraryBuilder()
) {
    private val mutex = Mutex()

    suspend fun scan(): ScanReport = mutex.withLock {
        withContext(dispatcher) {
            val roles = accounts.getAccounts().associate { it.id.value to it.role }
            val results = accounts.getWatchedRoots().map { root ->
                val role = roles[root.accountId]
                val outcome = if (role == null) {
                    RootScanOutcome.Failed(ScanFailure.UnknownAccount)
                } else {
                    scanRoot(root.accountId, root.driveFolderId, role)
                }
                RootScanResult(root.accountId, root.driveFolderId, root.name, outcome)
            }
            val purged = purge()
            ScanReport(results, purged)
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
    }
}
