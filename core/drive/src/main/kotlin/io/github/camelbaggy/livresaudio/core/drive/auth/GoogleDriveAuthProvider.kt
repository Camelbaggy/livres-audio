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
    private val about: AboutFetcher
) : DriveAuthProvider {
    /** Last token given per account, kept in memory only, to be able to invalidate it on `forceRefresh`. */
    private val lastTokens = ConcurrentHashMap<AccountId, String>()

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
        lastTokens[account.id] = token.value
        return AuthResult.Success(account)
    }

    override suspend fun removeAccount(accountId: AccountId) {
        lastTokens.remove(accountId)?.let { gateway.clearToken(it) }
        store.remove(accountId)
    }

    override suspend fun getAccessToken(accountId: AccountId, forceRefresh: Boolean): AuthResult<AccessToken> {
        val account = store.find(accountId) ?: return AuthResult.Failure("compte inconnu")
        if (forceRefresh) lastTokens[accountId]?.let { gateway.clearToken(it) }
        return when (val result = gateway.authorize(account.email, ui = null)) {
            is GatewayResult.Granted -> {
                lastTokens[accountId] = result.token.value
                AuthResult.Success(result.token)
            }

            GatewayResult.ConsentRequired -> AuthResult.NeedsUserAction

            GatewayResult.Cancelled -> AuthResult.NeedsUserAction

            GatewayResult.NetworkUnavailable -> AuthResult.NetworkUnavailable

            is GatewayResult.Failed -> AuthResult.Failure(result.message)
        }
    }

    private fun DriveError.toAuthFailure(): AuthResult<Nothing> = when (this) {
        DriveError.NetworkUnavailable -> AuthResult.NetworkUnavailable
        DriveError.AuthRequired -> AuthResult.NeedsUserAction
        else -> AuthResult.Failure(describe())
    }
}
