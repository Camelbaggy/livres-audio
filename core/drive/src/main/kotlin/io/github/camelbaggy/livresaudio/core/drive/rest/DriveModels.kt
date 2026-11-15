package io.github.camelbaggy.livresaudio.core.drive.rest

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON configuration shared by Retrofit and the manual encoders. Unknown fields are ignored. */
val DriveJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
const val APP_DATA_FOLDER = "appDataFolder"

@Serializable
data class DriveUser(val displayName: String? = null, val emailAddress: String? = null)

@Serializable
data class AboutResponse(val user: DriveUser? = null)

@Serializable
data class DriveFile(
    val id: String,
    val name: String? = null,
    val mimeType: String? = null,
    val modifiedTime: String? = null,
    val parents: List<String>? = null,
    val md5Checksum: String? = null,
    val size: String? = null,
    val lastModifyingUser: DriveUser? = null,
    val trashed: Boolean? = null
)

@Serializable
data class DriveFileList(val files: List<DriveFile> = emptyList(), val nextPageToken: String? = null)

/** Metadata sent when creating a file or folder. */
@Serializable
data class DriveFileMetadata(val name: String, val mimeType: String? = null, val parents: List<String>? = null)

@Serializable
data class StartPageToken(val startPageToken: String)

/** One entry of `changes.list`. [file] is absent when [removed] (deleted for good or access lost). */
@Serializable
data class DriveChange(val fileId: String? = null, val removed: Boolean? = null, val file: DriveFile? = null)

/** A page of `changes.list`: [nextPageToken] while more pages follow, [newStartPageToken] on the last one. */
@Serializable
data class DriveChangeList(
    val changes: List<DriveChange> = emptyList(),
    val nextPageToken: String? = null,
    val newStartPageToken: String? = null
)

/** All changes since a token, and the token to store once they have been applied. */
data class DriveChangeSet(val changes: List<DriveChange>, val newStartPageToken: String)
