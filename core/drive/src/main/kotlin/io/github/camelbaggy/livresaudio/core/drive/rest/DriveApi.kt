package io.github.camelbaggy.livresaudio.core.drive.rest

import io.github.camelbaggy.livresaudio.core.model.AccountId
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Tag
import retrofit2.http.Url

/** Upload endpoint, relative to `https://www.googleapis.com/drive/v3/`. */
const val UPLOAD_FILES_PATH = "../../upload/drive/v3/files"

/** Request tag telling the OkHttp interceptor which account's token to use. */
class AccountTag(val accountId: AccountId)

/**
 * Minimal Drive v3 REST surface. Base URL: `https://www.googleapis.com/drive/v3/`;
 * uploads go through [UPLOAD_FILES_PATH] (relative, resolved against the base URL).
 */
interface DriveApi {
    @GET("about")
    suspend fun about(@Query("fields") fields: String, @Tag tag: AccountTag): Response<AboutResponse>

    /** Used right after consent, when the account is not yet known to the rest of the app. */
    @GET("about")
    suspend fun aboutWithToken(
        @Query("fields") fields: String,
        @Header("Authorization") authorization: String
    ): Response<AboutResponse>

    @GET("files")
    suspend fun listFiles(
        @Query("q") query: String?,
        @Query("fields") fields: String,
        @Query("pageSize") pageSize: Int,
        @Query("pageToken") pageToken: String?,
        @Query("spaces") spaces: String?,
        @Tag tag: AccountTag
    ): Response<DriveFileList>

    @GET("files/{id}")
    suspend fun getFile(
        @Path("id") id: String,
        @Query("fields") fields: String,
        @Tag tag: AccountTag
    ): Response<DriveFile>

    @GET("changes/startPageToken")
    suspend fun getStartPageToken(@Tag tag: AccountTag): Response<StartPageToken>

    @GET("changes")
    suspend fun listChanges(
        @Query("pageToken") pageToken: String,
        @Query("fields") fields: String,
        @Query("pageSize") pageSize: Int,
        @Query("includeRemoved") includeRemoved: Boolean,
        @Tag tag: AccountTag
    ): Response<DriveChangeList>

    @Streaming
    @GET("files/{id}")
    suspend fun download(
        @Path("id") id: String,
        @Query("alt") alt: String,
        @Tag tag: AccountTag
    ): Response<ResponseBody>

    /** Metadata-only creation (folders). */
    @POST("files")
    suspend fun createMetadata(
        @Query("fields") fields: String,
        @Body body: RequestBody,
        @Tag tag: AccountTag
    ): Response<DriveFile>

    /** `uploadType=multipart`: the body is a `multipart/related` (metadata JSON + content). */
    @POST
    suspend fun createMultipart(
        @Url url: String,
        @Query("uploadType") uploadType: String,
        @Query("fields") fields: String,
        @Body body: RequestBody,
        @Tag tag: AccountTag
    ): Response<DriveFile>

    /** `uploadType=media`: replaces the content of an existing file. */
    @PATCH
    suspend fun updateContent(
        @Url url: String,
        @Query("uploadType") uploadType: String,
        @Query("fields") fields: String,
        @Body body: RequestBody,
        @Tag tag: AccountTag
    ): Response<DriveFile>
}
