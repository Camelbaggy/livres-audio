package io.github.camelbaggy.livresaudio.core.drive.auth

import io.github.camelbaggy.livresaudio.core.drive.rest.DriveError
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveResult
import io.github.camelbaggy.livresaudio.core.drive.rest.DriveUser
import io.github.camelbaggy.livresaudio.core.drive.rest.describe
import io.github.camelbaggy.livresaudio.core.model.AccessToken
import io.github.camelbaggy.livresaudio.core.model.AccountId
import io.github.camelbaggy.livresaudio.core.model.AccountRole
import io.github.camelbaggy.livresaudio.core.model.AuthResult
import io.github.camelbaggy.livresaudio.core.model.AuthUi
import io.github.camelbaggy.livresaudio.core.model.ConnectedAccount
import io.github.camelbaggy.livresaudio.core.model.DriveAuthProvider
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Calls `about.get` with a token not yet bound to a stored account. */
fun interface AboutFetcher {
    suspend fun fetch(token: AccessToken): DriveResult<DriveUser>
}

/**
 * [DriveAuthProvider] based on Google Identity Services `AuthorizationClient` (CLAUDE.md §5.1).
 * The account is targeted by its address (`setAccount`), which is what makes several accounts possible.
 */
class GoogleDriveAuthProvider internal constructor(
    private val store: AccountStore,
    private val gateway: AuthorizationGateway,
    private val clock: () -> Long = System::currentTimeMillis,
    private val about: AboutFetcher
) : DriveAuthProvider {
    private class CachedToken(val token: AccessToken, val obtainedAtMs: Long)

    /**
     * Last token given per account, with the time it was obtained. Memory only: never persisted, never logged.
     * It saves one Play Services round trip per HTTP request. It is reused for [TOKEN_TTL_MS] (Google tokens last
     * about an hour); a token that expired earlier than that gets a 401, and the OkHttp `Authenticator` then asks
     * for `forceRefresh`. An entry is kept after its TTL so that `forceRefresh` can still `clearToken` it.
     */
    private val lastTokens = ConcurrentHashMap<AccountId, CachedToken>()

    /** One lock per account: concurrent callers wait for a single `authorize` instead of making their own. */
    private val locks = ConcurrentHashMap<AccountId, Mutex>()

    private fun remember(accountId: AccountId, token: AccessToken) {
        lastTokens[accountId] = CachedToken(token, clock())
    }

    override suspend fun addAccount(ui: AuthUi, role: AccountRole): AuthResult<ConnectedAccount> {
        val activityUi = ui as? ActivityAuthUi
            ?: return AuthResult.Failure("interface d'authentification incompatible")
        val chosen = activityUi.chooseGoogleAccount() ?: return AuthResult.Cancelled
        val token = when (val granted = gateway.authorize(chosen, activityUi)) {
            is GatewayResult.Granted -> granted.token
            GatewayResult.Cancelled -> return AuthResult.Cancelled
            GatewayResult.ConsentRequired -> return AuthResult.NeedsUserAction
            GatewayResult.NetworkUnavailable -> return AuthResult.NetworkUnavailable
            is GatewayResult.Failed -> return AuthResult.Failure(granted.message)
        }
        val user = when (val result = about.fetch(token)) {
            is DriveResult.Success -> result.value
            is DriveResult.Failure -> return result.error.toAuthFailure()
        }
        val account = store.upsert(user.emailAddress ?: chosen, user.displayName, role)
        remember(account.id, token)
        return AuthResult.Success(account)
    }

    override suspend fun reconnectAccount(ui: AuthUi, accountId: AccountId): AuthResult<ConnectedAccount> {
        val activityUi = ui as? ActivityAuthUi
            ?: return AuthResult.Failure("interface d'authentification incompatible")
        val account = store.find(accountId) ?: return AuthResult.Failure("compte inconnu")
        lastTokens.remove(accountId)?.let { gateway.clearToken(it.token.value) }
        val token = when (val granted = gateway.authorize(account.email, activityUi)) {
            is GatewayResult.Granted -> granted.token
            GatewayResult.Cancelled -> return AuthResult.Cancelled
            GatewayResult.ConsentRequired -> return AuthResult.NeedsUserAction
            GatewayResult.NetworkUnavailable -> return AuthResult.NetworkUnavailable
            is GatewayResult.Failed -> return AuthResult.Failure(granted.message)
        }
        val user = when (val result = about.fetch(token)) {
            is DriveResult.Success -> result.value
            is DriveResult.Failure -> return result.error.toAuthFailure()
        }
        // The consent is bound to the address (`setAccount`); a different answer must never rebind the account.
        if (user.emailAddress != null && !user.emailAddress.equals(account.email, ignoreCase = true)) {
            return AuthResult.Failure("adresse différente de celle du compte")
        }
        val updated = store.upsert(account.email, user.displayName, account.role)
        remember(updated.id, token)
        return AuthResult.Success(updated)
    }

    override suspend fun removeAccount(accountId: AccountId) {
        lastTokens.remove(accountId)?.let { gateway.clearToken(it.token.value) }
        store.remove(accountId)
    }

    override suspend fun getAccessToken(accountId: AccountId, forceRefresh: Boolean): AuthResult<AccessToken> {
        val account = store.find(accountId) ?: return AuthResult.Failure("compte inconnu")
        return locks.getOrPut(accountId) { Mutex() }.withLock {
            val cached = lastTokens[accountId]
            if (!forceRefresh && cached != null && clock() - cached.obtainedAtMs < TOKEN_TTL_MS) {
                return@withLock AuthResult.Success(cached.token)
            }
            if (forceRefresh) lastTokens.remove(accountId)?.let { gateway.clearToken(it.token.value) }
            when (val result = gateway.authorize(account.email, ui = null)) {
                is GatewayResult.Granted -> {
                    remember(accountId, result.token)
                    AuthResult.Success(result.token)
                }

                GatewayResult.ConsentRequired -> AuthResult.NeedsUserAction

                GatewayResult.Cancelled -> AuthResult.NeedsUserAction

                GatewayResult.NetworkUnavailable -> AuthResult.NetworkUnavailable

                is GatewayResult.Failed -> AuthResult.Failure(result.message)
            }
        }
    }

    private fun DriveError.toAuthFailure(): AuthResult<Nothing> = when (this) {
        DriveError.NetworkUnavailable -> AuthResult.NetworkUnavailable
        DriveError.AuthRequired -> AuthResult.NeedsUserAction
        else -> AuthResult.Failure(describe())
    }

    private companion object {
        const val TOKEN_TTL_MS = 45L * 60 * 1000
    }
}
