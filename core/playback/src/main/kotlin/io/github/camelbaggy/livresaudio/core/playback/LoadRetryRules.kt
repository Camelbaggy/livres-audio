package io.github.camelbaggy.livresaudio.core.playback

/** What went wrong while loading, reduced to what the retry decision needs. */
sealed interface LoadFailure {
    /** Connection problem, timeout, interrupted transfer, token temporarily unobtainable (no network). */
    data object Network : LoadFailure

    data class Http(val code: Int) : LoadFailure

    /** The token cannot be renewed without the user: retrying in a loop would be pointless. */
    data object AuthBlocked : LoadFailure

    /** Anything else (corrupt data, unsupported format…): left to the default policy. */
    data object Other : LoadFailure
}

/** Outcome of [LoadRetryRules.decide]. */
sealed interface RetryDecision {
    data class RetryAfter(val delayMs: Long) : RetryDecision

    data object Fatal : RetryDecision

    /** Not ours to decide: ask the default policy. */
    data object UseDefault : RetryDecision
}

/**
 * Pure retry rules for the trial (CLAUDE.md §5.4): network errors are retried without limit with a delay
 * growing from 1 s and capped at 8 s, so the player waits instead of failing; HTTP 404 is fatal.
 */
object LoadRetryRules {
    const val BASE_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 8_000L

    /** 1 s, 2 s, 4 s, 8 s, 8 s… for `errorCount` = 1, 2, 3… */
    fun backoffMs(errorCount: Int): Long {
        val exponent = (errorCount - 1).coerceIn(0, 3)
        return (BASE_DELAY_MS shl exponent).coerceAtMost(MAX_DELAY_MS)
    }

    fun decide(failure: LoadFailure, errorCount: Int): RetryDecision = when (failure) {
        LoadFailure.Network -> RetryDecision.RetryAfter(backoffMs(errorCount))

        LoadFailure.AuthBlocked -> RetryDecision.Fatal

        LoadFailure.Other -> RetryDecision.UseDefault

        is LoadFailure.Http -> when {
            failure.code == 404 -> RetryDecision.Fatal
            isTransientHttp(failure.code) -> RetryDecision.RetryAfter(backoffMs(errorCount))
            else -> RetryDecision.Fatal
        }
    }

    /** Server-side or throttling statuses that usually go away on their own. */
    private fun isTransientHttp(code: Int): Boolean = code in 500..599 || code == 408 || code == 429
}
