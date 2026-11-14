package io.github.camelbaggy.livresaudio.onboarding

import io.github.camelbaggy.livresaudio.core.drive.rest.DriveError
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveRestClient
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveResult
import io.github.camelbaggy.livresaudio.core.drive.rest.FOLDER_MIME_TYPE
import io.github.camelbaggy.livresaudio.core.drive.rest.describe
import io.github.camelbaggy.livresaudio.core.model.AccountId

/** A Drive folder shown in the folder chooser. */
data class FolderEntry(val id: String, val name: String)

sealed interface FolderError {
    data object NetworkUnavailable : FolderError

    /** The user must reconnect the account. */
    data object AuthRequired : FolderError

    data class Other(val description: String) : FolderError
}

sealed interface FolderResult<out T> {
    data class Success<T>(val value: T) : FolderResult<T>

    data class Failure(val error: FolderError) : FolderResult<Nothing>
}

/** Folder navigation in Drive, seen by the onboarding screens (faked in tests). */
interface FolderBrowser {
    /** The "My Drive" root, with its real Drive id (never the `root` alias). */
    suspend fun rootFolder(accountId: AccountId): FolderResult<FolderEntry>

    /** Sub-folders of [parentId], naturally sorted by name. */
    suspend fun listFolders(accountId: AccountId, parentId: String): FolderResult<List<FolderEntry>>
}

internal const val ROOT_FOLDER_NAME = "Mon Drive"

/** `files.list` query for the non-trashed sub-folders of [parentId]. */
fun foldersQuery(parentId: String): String =
    "'$parentId' in parents and mimeType = '$FOLDER_MIME_TYPE' and trashed = false"

class DriveFolderBrowser(private val drive: DriveRestClient) : FolderBrowser {
    override suspend fun rootFolder(accountId: AccountId): FolderResult<FolderEntry> =
        when (val r = drive.getFile(accountId, "root")) {
            is DriveResult.Failure -> FolderResult.Failure(r.error.toFolderError())
            is DriveResult.Success -> FolderResult.Success(FolderEntry(r.value.id, ROOT_FOLDER_NAME))
        }

    override suspend fun listFolders(accountId: AccountId, parentId: String): FolderResult<List<FolderEntry>> =
        when (val r = drive.listAllFiles(accountId, foldersQuery(parentId))) {
            is DriveResult.Failure -> FolderResult.Failure(r.error.toFolderError())

            is DriveResult.Success -> FolderResult.Success(
                r.value.map { FolderEntry(it.id, it.name ?: "?") }.sortedNaturally()
            )
        }
}

internal fun DriveError.toFolderError(): FolderError = when (this) {
    DriveError.NetworkUnavailable -> FolderError.NetworkUnavailable
    DriveError.AuthRequired -> FolderError.AuthRequired
    else -> FolderError.Other(describe())
}

private val CHUNK = Regex("\\d+|\\D+")

/** Natural order: `2` before `10`, case-insensitive. Pure. */
fun naturalCompare(a: String, b: String): Int {
    val ca = CHUNK.findAll(a.lowercase()).map { it.value }.toList()
    val cb = CHUNK.findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(ca.size, cb.size)) {
        val x = ca[i]
        val y = cb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) {
            val nx = x.trimStart('0')
            val ny = y.trimStart('0')
            if (nx.length != ny.length) nx.length.compareTo(ny.length) else nx.compareTo(ny)
        } else {
            x.compareTo(y)
        }
        if (c != 0) return c
    }
    return ca.size.compareTo(cb.size)
}

fun List<FolderEntry>.sortedNaturally(): List<FolderEntry> = sortedWith { a, b -> naturalCompare(a.name, b.name) }
