package io.github.camelbaggy.livresaudio.core.library.scan

import io.github.camelbaggy.livresaudio.core.drive.rest.DriveError
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveFile
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveRestClient
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveResult
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.DriveItem
import java.time.Instant

/** Lists everything below a Drive folder. Seam of [LibraryScanner] (faked in tests). */
fun interface TreeLister {
    /** Success only if the WHOLE tree was listed: a partial listing is a failure (never a truncated success). */
    suspend fun list(accountId: AccountId, rootFolderId: String): DriveResult<List<DriveItem>>
}

/**
 * Breadth-first recursive listing of a watched root (CLAUDE.md §5.2). One query covers up to [batchSize] folders
 * (`'a' in parents or 'b' in parents`) to cut the number of calls; pages are followed to the end.
 *
 * Any error (network, quota, auth...) aborts the listing and is returned as is: the caller must then treat the
 * walk as incomplete. `changes.list` does not replace this
 * class: [LibraryScanner] only decides WHICH roots are listed again (a touched root is listed entirely).
 */
class DriveTreeLister(
    private val client: DriveRestClient,
    private val batchSize: Int = MAX_PARENTS_PER_QUERY,
    private val pageSize: Int = PAGE_SIZE,
    private val maxPagesPerQuery: Int = MAX_PAGES_PER_QUERY
) : TreeLister {
    init {
        require(batchSize in 1..MAX_PARENTS_PER_QUERY) { "batchSize must be in 1..$MAX_PARENTS_PER_QUERY" }
    }

    override suspend fun list(accountId: AccountId, rootFolderId: String): DriveResult<List<DriveItem>> {
        val items = LinkedHashMap<String, DriveItem>()
        val visitedFolders = mutableSetOf(rootFolderId)
        var queue: List<String> = listOf(rootFolderId)
        while (queue.isNotEmpty()) {
            val next = mutableListOf<String>()
            for (batch in queue.chunked(batchSize)) {
                val children = when (val r = listChildren(accountId, batch)) {
                    is DriveResult.Failure -> return r
                    is DriveResult.Success -> r.value
                }
                val parents = batch.toSet()
                for (file in children) {
                    val item = file.toItem(parents) ?: continue
                    if (items.putIfAbsent(item.id, item) == null && item.isFolder && visitedFolders.add(item.id)) {
                        next += item.id
                    }
                }
            }
            queue = next
        }
        return DriveResult.Success(items.values.toList())
    }

    private suspend fun listChildren(accountId: AccountId, parentIds: List<String>): DriveResult<List<DriveFile>> {
        val query = parentIds.joinToString(" or ", prefix = "(", postfix = ") and trashed=false") {
            "'${escape(it)}' in parents"
        }
        val all = mutableListOf<DriveFile>()
        var token: String? = null
        repeat(maxPagesPerQuery) {
            when (val page = client.listFiles(accountId, query, pageSize = pageSize, pageToken = token)) {
                is DriveResult.Failure -> return page

                is DriveResult.Success -> {
                    all += page.value.files
                    token = page.value.nextPageToken
                    if (token == null) return DriveResult.Success(all)
                }
            }
        }
        // Still more pages: refuse to pretend the listing is complete.
        return DriveResult.Failure(DriveError.Unexpected("listing trop volumineux (plus de $maxPagesPerQuery pages)"))
    }

    private fun DriveFile.toItem(queriedParents: Set<String>): DriveItem? {
        val parent = parents.orEmpty().firstOrNull { it in queriedParents } ?: parents?.firstOrNull() ?: return null
        return DriveItem(
            id = id,
            name = name.orEmpty(),
            mimeType = mimeType.orEmpty(),
            parentId = parent,
            sizeBytes = size?.toLongOrNull(),
            md5 = md5Checksum,
            modifiedTimeMs = modifiedTime?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        )
    }

    private fun escape(id: String) = id.replace("\\", "\\\\").replace("'", "\\'")

    companion object {
        /** Drive accepts long queries; 20 parents keeps the URL short and pages small. */
        const val MAX_PARENTS_PER_QUERY = 20
        const val PAGE_SIZE = 1000
        const val MAX_PAGES_PER_QUERY = 200
    }
}
