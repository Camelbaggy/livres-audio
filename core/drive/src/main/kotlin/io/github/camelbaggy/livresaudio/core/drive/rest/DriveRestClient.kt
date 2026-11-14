package io.github.camelbaggy.livresaudio.core.drive.rest

import io.github.camelbaggy.livresaudio.core.model.AccessToken
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.AuthResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
private const val FILE_FIELDS =
    "id,name,mimeType,modifiedTime,parents,md5Checksum,size,lastModifyingUser(displayName,emailAddress)"
private const val LIST_FIELDS = "nextPageToken,files($FILE_FIELDS)"
private const val ABOUT_FIELDS = "user(emailAddress,displayName)"
private const val MAX_PAGES = 50

/** `multipart/related` body expected by Drive's `uploadType=multipart`: metadata part, then content part. */
fun buildMultipartRelated(metadataJson: String, content: String, contentMimeType: String): MultipartBody =
    MultipartBody.Builder()
        .setType("multipart/related".toMediaType())
        .addPart(metadataJson.toRequestBody(JSON_TYPE))
        .addPart(content.toRequestBody("$contentMimeType; charset=utf-8".toMediaType()))
        .build()

/** Typed Drive client. Every method returns a [DriveResult]; no exception escapes. */
class DriveRestClient(private val api: DriveApi) {
    suspend fun about(accountId: AccountId): DriveResult<DriveUser> =
        call { api.about(ABOUT_FIELDS, AccountTag(accountId)) }.mapUser()

    suspend fun aboutWithToken(token: AccessToken): DriveResult<DriveUser> =
        call { api.aboutWithToken(ABOUT_FIELDS, "Bearer ${token.value}") }.mapUser()

    suspend fun listFiles(
        accountId: AccountId,
        query: String? = null,
        spaces: String? = null,
        pageSize: Int = 100,
        pageToken: String? = null
    ): DriveResult<DriveFileList> =
        call { api.listFiles(query, LIST_FIELDS, pageSize, pageToken, spaces, AccountTag(accountId)) }

    /** Follows `nextPageToken` (at most [MAX_PAGES] pages). */
    suspend fun listAllFiles(
        accountId: AccountId,
        query: String? = null,
        spaces: String? = null
    ): DriveResult<List<DriveFile>> {
        val all = mutableListOf<DriveFile>()
        var token: String? = null
        repeat(MAX_PAGES) {
            when (val page = listFiles(accountId, query, spaces, pageToken = token)) {
                is DriveResult.Failure -> return page

                is DriveResult.Success -> {
                    all += page.value.files
                    token = page.value.nextPageToken
                    if (token == null) return DriveResult.Success(all)
                }
            }
        }
        return DriveResult.Success(all)
    }

    suspend fun getFile(accountId: AccountId, fileId: String): DriveResult<DriveFile> =
        call { api.getFile(fileId, FILE_FIELDS, AccountTag(accountId)) }

    suspend fun downloadText(accountId: AccountId, fileId: String): DriveResult<String> =
        when (val r = call { api.download(fileId, "media", AccountTag(accountId)) }) {
            is DriveResult.Failure -> r
            is DriveResult.Success -> readBody { r.value.string() }
        }

    /** Creates a folder; [parentId] null = My Drive root. */
    suspend fun createFolder(accountId: AccountId, name: String, parentId: String?): DriveResult<DriveFile> {
        val meta = DriveFileMetadata(name, FOLDER_MIME_TYPE, parentId?.let { listOf(it) })
        val body: RequestBody = DriveJson.encodeToString(meta).toRequestBody(JSON_TYPE)
        return call { api.createMetadata(FILE_FIELDS, body, AccountTag(accountId)) }
    }

    /** Creates a JSON file in [parentId] (a folder id or [APP_DATA_FOLDER]). */
    suspend fun createJsonFile(
        accountId: AccountId,
        name: String,
        parentId: String,
        content: String
    ): DriveResult<DriveFile> {
        val meta = DriveJson.encodeToString(DriveFileMetadata(name, "application/json", listOf(parentId)))
        val body = buildMultipartRelated(meta, content, "application/json")
        return call { api.createMultipart(UPLOAD_FILES_PATH, "multipart", FILE_FIELDS, body, AccountTag(accountId)) }
    }

    suspend fun updateFileContent(accountId: AccountId, fileId: String, content: String): DriveResult<DriveFile> {
        val body = content.toRequestBody(JSON_TYPE)
        return call {
            api.updateContent("$UPLOAD_FILES_PATH/$fileId", "media", FILE_FIELDS, body, AccountTag(accountId))
        }
    }

    private fun DriveResult<AboutResponse>.mapUser(): DriveResult<DriveUser> = when (this) {
        is DriveResult.Failure -> this

        is DriveResult.Success -> value.user?.let { DriveResult.Success(it) }
            ?: DriveResult.Failure(DriveError.Unexpected("réponse about sans utilisateur"))
    }

    private inline fun readBody(read: () -> String): DriveResult<String> = try {
        DriveResult.Success(read())
    } catch (e: IOException) {
        DriveResult.Failure(DriveError.NetworkUnavailable)
    }

    private suspend fun <T : Any> call(block: suspend () -> Response<T>): DriveResult<T> = try {
        val response = block()
        if (response.isSuccessful) {
            response.body()?.let { DriveResult.Success(it) }
                ?: DriveResult.Failure(DriveError.Unexpected("réponse vide"))
        } else {
            DriveResult.Failure(mapHttpError(response.code(), response.errorBody()?.string()))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AuthFailedException) {
        DriveResult.Failure(
            when (e.result) {
                AuthResult.NetworkUnavailable -> DriveError.NetworkUnavailable
                AuthResult.NeedsUserAction, AuthResult.Cancelled -> DriveError.AuthRequired
                else -> DriveError.Unexpected("jeton indisponible")
            }
        )
    } catch (e: IOException) {
        DriveResult.Failure(DriveError.NetworkUnavailable)
    } catch (e: SerializationException) {
        DriveResult.Failure(DriveError.Unexpected("réponse illisible"))
    }
}
