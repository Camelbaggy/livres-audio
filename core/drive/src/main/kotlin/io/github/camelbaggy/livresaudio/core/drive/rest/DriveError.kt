package io.github.camelbaggy.livresaudio.core.drive.rest

/** Typed Drive failure: no exception ever reaches the UI (CLAUDE.md §11.3). */
sealed interface DriveError {
    /** No network or timeout: retry later, never disconnect the account for this. */
    data object NetworkUnavailable : DriveError

    /** The token could not be renewed: the user must reconnect the account. */
    data object AuthRequired : DriveError

    data class NotFound(val message: String) : DriveError

    data class Forbidden(val reason: String?, val message: String) : DriveError

    /** Quota exceeded even after the retries. */
    data class RateLimited(val message: String) : DriveError

    data class Server(val code: Int) : DriveError

    data class Http(val code: Int, val message: String) : DriveError

    data class Unexpected(val message: String) : DriveError
}

sealed interface DriveResult<out T> {
    data class Success<T>(val value: T) : DriveResult<T>

    data class Failure(val error: DriveError) : DriveResult<Nothing>
}

/** Short French description for logs and screens. Never contains a token. */
fun DriveError.describe(): String = when (this) {
    DriveError.NetworkUnavailable -> "réseau indisponible"
    DriveError.AuthRequired -> "autorisation à renouveler (reconnecter le compte)"
    is DriveError.NotFound -> "introuvable (404) : $message"
    is DriveError.Forbidden -> "refusé (403, ${reason ?: "sans motif"}) : $message"
    is DriveError.RateLimited -> "quota dépassé : $message"
    is DriveError.Server -> "erreur serveur ($code)"
    is DriveError.Http -> "erreur HTTP $code : $message"
    is DriveError.Unexpected -> "erreur inattendue : $message"
}
