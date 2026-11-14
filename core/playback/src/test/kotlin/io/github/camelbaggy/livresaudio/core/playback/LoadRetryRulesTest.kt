package io.github.camelbaggy.livresaudio.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class LoadRetryRulesTest {
    @Test
    fun backoffGrowsAndIsCappedAt8Seconds() {
        val expected = listOf(1 to 1_000L, 2 to 2_000L, 3 to 4_000L, 4 to 8_000L, 5 to 8_000L, 50 to 8_000L)
        expected.forEach { (count, delay) -> assertEquals(delay, LoadRetryRules.backoffMs(count), "errorCount=$count") }
    }

    @Test
    fun backoffToleratesNonPositiveCounts() {
        assertEquals(1_000L, LoadRetryRules.backoffMs(0))
        assertEquals(1_000L, LoadRetryRules.backoffMs(-3))
    }

    @Test
    fun networkErrorsAreRetriedWithoutLimit() {
        (1..10_000 step 997).forEach { count ->
            val decision = LoadRetryRules.decide(LoadFailure.Network, count)
            assertEquals(RetryDecision.RetryAfter(LoadRetryRules.backoffMs(count)), decision)
        }
    }

    @Test
    fun http404IsFatal() {
        assertEquals(RetryDecision.Fatal, LoadRetryRules.decide(LoadFailure.Http(404), 1))
    }

    @Test
    fun httpStatusesAreClassified() {
        val table = mapOf(
            401 to RetryDecision.Fatal,
            403 to RetryDecision.Fatal,
            404 to RetryDecision.Fatal,
            416 to RetryDecision.Fatal,
            408 to RetryDecision.RetryAfter(1_000),
            429 to RetryDecision.RetryAfter(1_000),
            500 to RetryDecision.RetryAfter(1_000),
            503 to RetryDecision.RetryAfter(1_000)
        )
        table.forEach { (code, expected) ->
            assertEquals(expected, LoadRetryRules.decide(LoadFailure.Http(code), 1), "HTTP $code")
        }
    }

    @Test
    fun authBlockedIsFatalAndOtherUsesDefault() {
        assertEquals(RetryDecision.Fatal, LoadRetryRules.decide(LoadFailure.AuthBlocked, 1))
        assertEquals(RetryDecision.UseDefault, LoadRetryRules.decide(LoadFailure.Other, 1))
    }
}
