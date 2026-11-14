package io.github.camelbaggy.livresaudio.core.model

/** Local identifier of a connected Google account (stable, never the e-mail address). */
@JvmInline
value class AccountId(val value: String)

/** A connected Google account (CLAUDE.md §4.1). */
data class ConnectedAccount(
    val id: AccountId,
    val email: String,
    val displayName: String?,
    val role: AccountRole,
    val isProfile: Boolean
)

/**
 * OAuth access token. Never logged, never persisted in Room nor in synced files (CLAUDE.md §5.1):
 * [toString] hides the value.
 */
class AccessToken(val value: String, val expiresAtMs: Long?) {
    override fun toString(): String = "AccessToken(***)"
}

/** Explicit authentication outcome: errors never escape as exceptions (CLAUDE.md §11.3). */
sealed interface AuthResult<out T> {
    data class Success<T>(val value: T) : AuthResult<T>

    /** The user must act (consent screen, account chooser); the UI should offer to reconnect. */
    data object NeedsUserAction : AuthResult<Nothing>

    /** The user closed the consent screen or the account chooser. */
    data object Cancelled : AuthResult<Nothing>

    /** No network: retry later, never disconnect the account for this. */
    data object NetworkUnavailable : AuthResult<Nothing>

    data class Failure(val message: String) : AuthResult<Nothing>
}

/**
 * Bridge to the user interface for interactive steps (account chooser, consent).
 * Implemented in `app` with an Activity; `core:drive` extends it with Android types.
 */
interface AuthUi

/** The only entry point to Google authentication for the rest of the code (CLAUDE.md §5.1). */
interface DriveAuthProvider {
    /** Interactive: forced account chooser, consent for the D7 scopes, then `about.get`. */
    suspend fun addAccount(ui: AuthUi, role: AccountRole): AuthResult<ConnectedAccount>

    /**
     * Interactive: redoes the consent for the SAME already connected account (no account chooser, role and
     * [AccountId] unchanged). Fails if Google answers for another address.
     */
    suspend fun reconnectAccount(ui: AuthUi, accountId: AccountId): AuthResult<ConnectedAccount>

    suspend fun removeAccount(accountId: AccountId)

    /** Silent; [forceRefresh] after a 401. Never shows UI. */
    suspend fun getAccessToken(accountId: AccountId, forceRefresh: Boolean = false): AuthResult<AccessToken>
}
