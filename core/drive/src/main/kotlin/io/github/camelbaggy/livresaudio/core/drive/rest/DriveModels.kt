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
    val lastModifyingUser: DriveUser? = null
)

@Serializable
data class DriveFileList(val files: List<DriveFile> = emptyList(), val nextPageToken: String? = null)

/** Metadata sent when creating a file or folder. */
@Serializable
data class DriveFileMetadata(val name: String, val mimeType: String? = null, val parents: List<String>? = null)
