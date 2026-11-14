package io.github.camelbaggy.livresaudio.core.playback

import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.github.camelbaggy.livresaudio.core.drive.rest.AccountTag
import io.github.camelbaggy.livresaudio.core.drive.rest.AuthInterceptor
import io.github.camelbaggy.livresaudio.core.drive.rest.RetryInterceptor
import io.github.camelbaggy.livresaudio.core.drive.rest.TokenAuthenticator
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.DriveAuthProvider
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/** Internal header carrying the account id from the resolver to [AccountHeaderInterceptor]; never sent out. */
internal const val ACCOUNT_HEADER = "X-LA-Account"

/**
 * Turns the internal [ACCOUNT_HEADER] into an [AccountTag] and strips the header, so the existing
 * [AuthInterceptor] / [TokenAuthenticator] of `core:drive` add and renew the right account's token.
 * OkHttpDataSource gives no way to tag a request, hence the header.
 */
internal class AccountHeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val account = request.header(ACCOUNT_HEADER) ?: return chain.proceed(request)
        val tagged = request.newBuilder()
            .removeHeader(ACCOUNT_HEADER)
            .tag(AccountTag::class.java, AccountTag(AccountId(account)))
            .build()
        return chain.proceed(tagged)
    }
}

/**
 * Builds the player's data source chain (CLAUDE.md §5.4):
 * `CacheDataSource` (stream cache) -> `ResolvingDataSource` (drive:// to Drive URL + account) -> OkHttp
 * (bearer token, renewal on 401, backoff on 5xx/quota). No logging interceptor: tokens are never logged.
 */
@UnstableApi
object DriveDataSourceFactory {
    private const val CONNECT_TIMEOUT_S = 10L
    private const val READ_TIMEOUT_S = 20L

    fun createHttpClient(auth: () -> DriveAuthProvider, base: OkHttpClient = OkHttpClient()): OkHttpClient =
        base.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .addInterceptor(AccountHeaderInterceptor())
            .addInterceptor(RetryInterceptor())
            .addInterceptor(AuthInterceptor(auth))
            .authenticator(TokenAuthenticator(auth))
            .build()

    /** Cache key of a `drive://` data spec, `dataSpec.key` winning if set. */
    val cacheKeyFactory: CacheKeyFactory = CacheKeyFactory { dataSpec ->
        dataSpec.key
            ?: DriveMediaUri.parse(dataSpec.uri.toString())?.let(DriveMediaUri::cacheKey)
            ?: dataSpec.uri.toString()
    }

    fun create(httpClient: OkHttpClient, cache: Cache, filesUrl: String = DRIVE_FILES_URL): DataSource.Factory {
        val http = ResolvingDataSource.Factory(OkHttpDataSource.Factory(httpClient)) { dataSpec ->
            resolve(dataSpec, filesUrl)
        }
        return CacheDataSource.Factory()
            .setCache(cache)
            .setCacheKeyFactory(cacheKeyFactory)
            .setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    internal fun resolve(dataSpec: DataSpec, filesUrl: String): DataSpec {
        val ref = DriveMediaUri.parse(dataSpec.uri.toString())
            ?: throw IOException("URI de lecture invalide")
        return dataSpec.buildUpon()
            .setUri(DriveMediaUri.toHttpUrl(ref, filesUrl).toUri())
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + (ACCOUNT_HEADER to ref.accountId))
            .build()
    }
}
