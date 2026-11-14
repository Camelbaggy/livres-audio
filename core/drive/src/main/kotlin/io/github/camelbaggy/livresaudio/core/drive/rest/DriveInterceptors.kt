package io.github.camelbaggy.livresaudio.core.drive.rest

import io.github.camelbaggy.livresaudio.core.model.AuthResult
import io.github.camelbaggy.livresaudio.core.model.DriveAuthProvider
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** The token could not be obtained: carries the typed outcome up to the REST client. */
class AuthFailedException(val result: AuthResult<*>) : IOException("Jeton indisponible")

private const val PEEK_LIMIT = 64L * 1024

private fun Request.withBearer(token: String): Request = newBuilder().header("Authorization", "Bearer $token").build()

/**
 * Adds the bearer token of the account carried by the [AccountTag]. Requests without a tag, or already
 * carrying an `Authorization` header, pass through. OkHttp interceptors are synchronous, hence the
 * `runBlocking` (confined to OkHttp threads; tokens come from a silent call, never from the UI).
 */
class AuthInterceptor(private val auth: () -> DriveAuthProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null) return chain.proceed(request)
        val tag = request.tag(AccountTag::class.java) ?: return chain.proceed(request)
        return when (val result = runBlocking { auth().getAccessToken(tag.accountId) }) {
            is AuthResult.Success -> chain.proceed(request.withBearer(result.value.value))
            else -> throw AuthFailedException(result)
        }
    }
}

/** On 401: asks for a new token with `forceRefresh = true`, once only (no loop). */
class TokenAuthenticator(private val auth: () -> DriveAuthProvider) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null
        val tag = response.request.tag(AccountTag::class.java) ?: return null
        val result = runBlocking { auth().getAccessToken(tag.accountId, forceRefresh = true) }
        return if (result is AuthResult.Success) response.request.withBearer(result.value.value) else null
    }
}

/**
 * Exponential backoff on 5xx and 403 quota (`rateLimitExceeded` / `userRateLimitExceeded`):
 * [maxAttempts] attempts in total. Network errors are not retried here (the caller decides).
 */
class RetryInterceptor(
    private val maxAttempts: Int = 3,
    private val baseDelayMs: Long = 500,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 1
        while (true) {
            val response = chain.proceed(chain.request())
            if (attempt >= maxAttempts || !shouldRetry(response)) return response
            response.close()
            sleeper(baseDelayMs shl (attempt - 1))
            attempt++
        }
    }

    private fun shouldRetry(response: Response): Boolean = when {
        response.code in 500..599 -> true
        response.code == 403 -> isRateLimitReason(parseErrorBody(response.peekBody(PEEK_LIMIT).string()).reason)
        else -> false
    }
}
