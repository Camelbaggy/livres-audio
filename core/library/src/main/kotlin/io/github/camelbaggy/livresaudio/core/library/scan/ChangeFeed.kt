package io.github.camelbaggy.livresaudio.core.library.scan

import io.github.camelbaggy.livresaudio.core.data.repo.BookRootLookup
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveError
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveRestClient
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveResult
import io.github.camelbaggy.livresaudio.core.model.AccountId

/**
 * One change reported by Drive. [parents] is null when Drive gives none (item deleted for good or no longer
 * accessible); a trashed item keeps its parents.
 */
data class ChangeEntry(val fileId: String, val removed: Boolean, val parents: List<String>?)

/** Everything that happened since a token, and the token to store once it has been applied. */
data class ChangeBatch(val entries: List<ChangeEntry>, val newToken: String)

/** Seam over Drive `changes.*` (faked in tests). */
interface ChangeFeed {
    suspend fun startToken(accountId: AccountId): DriveResult<String>

    /** [DriveError.InvalidChangeToken] when the token is expired or unusable. */
    suspend fun changesSince(accountId: AccountId, token: String): DriveResult<ChangeBatch>

    /** Parents of a folder; empty when it is top-level or out of reach (404/403). */
    suspend fun parentsOf(accountId: AccountId, fileId: String): DriveResult<List<String>>
}

class DriveChangeFeed(private val client: DriveRestClient) : ChangeFeed {
    override suspend fun startToken(accountId: AccountId) = client.getStartPageToken(accountId)

    override suspend fun changesSince(accountId: AccountId, token: String): DriveResult<ChangeBatch> =
        when (val r = client.listAllChanges(accountId, token)) {
            is DriveResult.Failure -> r

            is DriveResult.Success -> DriveResult.Success(
                ChangeBatch(
                    entries = r.value.changes.mapNotNull { c ->
                        val id = c.fileId ?: c.file?.id ?: return@mapNotNull null
                        val removed = c.removed == true
                        ChangeEntry(id, removed, if (removed) null else c.file?.parents.orEmpty())
                    },
                    newToken = r.value.newStartPageToken
                )
            )
        }

    override suspend fun parentsOf(accountId: AccountId, fileId: String): DriveResult<List<String>> =
        when (val r = client.getFile(accountId, fileId)) {
            is DriveResult.Success -> DriveResult.Success(r.value.parents.orEmpty())

            is DriveResult.Failure -> when (r.error) {
                is DriveError.NotFound, is DriveError.Forbidden -> DriveResult.Success(emptyList())
                else -> r
            }
        }
}

/**
 * Which watched roots does a batch of changes touch? An item belongs to a root when one of its ancestors is that
 * root. Ancestors of unknown folders are fetched with `files.get(parents)`, memoised for the run and bounded
 * ([MAX_LOOKUPS] calls, [MAX_DEPTH] levels). Items Drive no longer describes (permanently deleted, moved away)
 * are matched against the books already stored. Any network error is returned: the caller must change nothing.
 */
class ChangedRootsResolver(private val feed: ChangeFeed, private val bookRoots: BookRootLookup) {
    data class Resolution(val roots: Set<String>, val overflow: Boolean)

    suspend fun resolve(
        accountId: AccountId,
        entries: List<ChangeEntry>,
        watchedRootIds: Set<String>
    ): DriveResult<Resolution> {
        val touched = LinkedHashSet<String>()
        val memo = HashMap<String, String?>()
        var lookups = 0
        var overflow = false

        suspend fun rootOf(id: String, depth: Int): DriveResult<String?> {
            if (id in watchedRootIds) return DriveResult.Success(id)
            if (memo.containsKey(id)) return DriveResult.Success(memo[id])
            if (depth >= MAX_DEPTH) return DriveResult.Success(null)
            if (lookups >= MAX_LOOKUPS) {
                overflow = true
                return DriveResult.Success(null)
            }
            lookups++
            val parents = when (val p = feed.parentsOf(accountId, id)) {
                is DriveResult.Failure -> return p
                is DriveResult.Success -> p.value
            }
            var found: String? = null
            for (parent in parents) {
                when (val r = rootOf(parent, depth + 1)) {
                    is DriveResult.Failure -> return r

                    is DriveResult.Success -> if (r.value != null) {
                        found = r.value
                        break
                    }
                }
            }
            if (!overflow) memo[id] = found
            return DriveResult.Success(found)
        }

        for (entry in entries) {
            if (entry.fileId in watchedRootIds) touched += entry.fileId
            for (parent in entry.parents.orEmpty()) {
                if (overflow) break
                when (val r = rootOf(parent, 0)) {
                    is DriveResult.Failure -> return r
                    is DriveResult.Success -> r.value?.let { touched += it }
                }
            }
        }
        // Items that left a root (moved, deleted) no longer point to it: the stored books know where they were.
        touched += bookRoots.rootIdsFor(entries.map { it.fileId }).filter { it in watchedRootIds }
        return DriveResult.Success(Resolution(touched, overflow))
    }

    companion object {
        const val MAX_LOOKUPS = 150
        const val MAX_DEPTH = 32
    }
}
