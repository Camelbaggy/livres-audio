package io.github.camelbaggy.livresaudio.core.drive.rest

import kotlin.test.Test
import kotlin.test.assertEquals

class DriveErrorBodyTest {
    @Test
    fun parsesGoogleErrorBody() {
        val body = """{"error":{"code":403,"message":"Quota",""" +
            """"errors":[{"reason":"rateLimitExceeded","domain":"usageLimits"}]}}"""
        assertEquals(ParsedErrorBody(403, "Quota", "rateLimitExceeded"), parseErrorBody(body))
    }

    @Test
    fun toleratesMalformedOrEmptyBodies() {
        val empty = ParsedErrorBody(null, null, null)
        listOf(null, "", "   ", "<html>", """{"foo":1}""", """{"error":"texte"}""").forEach {
            assertEquals(empty, parseErrorBody(it), "entrée : $it")
        }
    }

    @Test
    fun mapsStatusCodes() {
        val quota = """{"error":{"message":"m","errors":[{"reason":"userRateLimitExceeded"}]}}"""
        assertEquals(DriveError.AuthRequired, mapHttpError(401, null))
        assertEquals(DriveError.NotFound("sans détail"), mapHttpError(404, ""))
        assertEquals(DriveError.RateLimited("m"), mapHttpError(403, quota))
        assertEquals(DriveError.Forbidden(null, "sans détail"), mapHttpError(403, null))
        assertEquals(DriveError.Server(502), mapHttpError(502, null))
        assertEquals(DriveError.Http(400, "sans détail"), mapHttpError(400, null))
    }
}
