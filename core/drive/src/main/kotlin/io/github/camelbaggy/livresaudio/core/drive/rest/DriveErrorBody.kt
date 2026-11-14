package io.github.camelbaggy.livresaudio.core.drive.rest

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Content of a Google API error body (`{"error":{"code":403,"message":"…","errors":[{"reason":"…"}]}}`). */
data class ParsedErrorBody(val code: Int?, val message: String?, val reason: String?)

private val RATE_LIMIT_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded")

/** Pure function: tolerant to any malformed body. */
fun parseErrorBody(body: String?): ParsedErrorBody {
    if (body.isNullOrBlank()) return ParsedErrorBody(null, null, null)
    return try {
        val error = DriveJson.parseToJsonElement(body).jsonObject["error"] as? JsonObject
            ?: return ParsedErrorBody(null, null, null)
        val reason = ((error["errors"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.get("reason")?.jsonPrimitive?.contentOrNull
        ParsedErrorBody(
            code = error["code"]?.jsonPrimitive?.intOrNull,
            message = error["message"]?.jsonPrimitive?.contentOrNull,
            reason = reason
        )
    } catch (_: Exception) {
        ParsedErrorBody(null, null, null)
    }
}

fun isRateLimitReason(reason: String?): Boolean = reason in RATE_LIMIT_REASONS

/** Maps an HTTP failure to a typed error. Pure. */
fun mapHttpError(code: Int, body: String?): DriveError {
    val parsed = parseErrorBody(body)
    val message = parsed.message ?: "sans détail"
    return when {
        code == 401 -> DriveError.AuthRequired
        code == 404 -> DriveError.NotFound(message)
        code == 403 && isRateLimitReason(parsed.reason) -> DriveError.RateLimited(message)
        code == 403 -> DriveError.Forbidden(parsed.reason, message)
        code in 500..599 -> DriveError.Server(code)
        else -> DriveError.Http(code, message)
    }
}
