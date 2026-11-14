package io.github.camelbaggy.livresaudio.core.drive.rest

import io.github.camelbaggy.livresaudio.core.model.DriveAuthProvider
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

const val DRIVE_BASE_URL = "https://www.googleapis.com/drive/v3/"

/**
 * Builds the REST stack. No logging interceptor: tokens and bodies are never logged.
 * [auth] is a lazy accessor so the provider and the client can reference each other.
 */
object DriveHttp {
    fun createClient(
        auth: () -> DriveAuthProvider,
        baseUrl: HttpUrl = DRIVE_BASE_URL.toHttpUrl(),
        retry: RetryInterceptor = RetryInterceptor(),
        okHttp: OkHttpClient = OkHttpClient()
    ): DriveRestClient {
        val client = okHttp.newBuilder()
            .addInterceptor(retry)
            .addInterceptor(AuthInterceptor(auth))
            .authenticator(TokenAuthenticator(auth))
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(DriveJson.asConverterFactory("application/json".toMediaType()))
            .build()
        return DriveRestClient(retrofit.create(DriveApi::class.java))
    }
}
