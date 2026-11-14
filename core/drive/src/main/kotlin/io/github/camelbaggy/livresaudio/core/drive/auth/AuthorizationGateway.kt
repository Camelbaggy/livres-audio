package io.github.camelbaggy.livresaudio.core.drive.auth

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import io.github.camelbaggy.livresaudio.core.model.AccessToken
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Drive scopes (decision D7): no scope allows modifying or deleting the books. */
val DRIVE_SCOPES: List<String> = listOf(
    "https://www.googleapis.com/auth/drive.readonly",
    "https://www.googleapis.com/auth/drive.appdata",
    "https://www.googleapis.com/auth/drive.file"
)

sealed interface GatewayResult {
    data class Granted(val token: AccessToken) : GatewayResult

    /** Consent must be (re)done interactively. */
    data object ConsentRequired : GatewayResult

    data object Cancelled : GatewayResult

    data object NetworkUnavailable : GatewayResult

    data class Failed(val message: String) : GatewayResult
}

/** Thin layer over Google Identity Services, replaceable in tests. */
interface AuthorizationGateway {
    /** Token for [email]; if consent is needed, uses [ui] when given, otherwise returns [GatewayResult.ConsentRequired]. */
    suspend fun authorize(email: String, ui: ActivityAuthUi?): GatewayResult

    /** Removes an invalid token from the Play Services cache (the next call asks the server). */
    suspend fun clearToken(token: String)
}

private const val GOOGLE_ACCOUNT_TYPE = "com.google"
private const val STATUS_SIGN_IN_CANCELLED = 12501

internal class GmsAuthorizationGateway(context: Context) : AuthorizationGateway {
    private val client = Identity.getAuthorizationClient(context.applicationContext)

    override suspend fun authorize(email: String, ui: ActivityAuthUi?): GatewayResult = guarded {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(DRIVE_SCOPES.map { Scope(it) })
            .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
            .build()
        val first = client.authorize(request).await()
        if (!first.hasResolution()) return@guarded first.toGranted()
        val pending = first.pendingIntent ?: return@guarded GatewayResult.Failed("consentement sans écran")
        if (ui == null) return@guarded GatewayResult.ConsentRequired
        val data = ui.launchConsent(pending) ?: return@guarded GatewayResult.Cancelled
        client.getAuthorizationResultFromIntent(data).toGranted()
    }

    override suspend fun clearToken(token: String) {
        try {
            client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: a stale cache entry only costs one more round trip.
        }
    }

    private fun AuthorizationResult.toGranted(): GatewayResult {
        val token = accessToken
        return if (token.isNullOrEmpty()) {
            GatewayResult.Failed("aucun jeton dans la réponse")
        } else {
            GatewayResult.Granted(AccessToken(token, null))
        }
    }

    private suspend fun guarded(block: suspend () -> GatewayResult): GatewayResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        when (e.statusCode) {
            CommonStatusCodes.NETWORK_ERROR -> GatewayResult.NetworkUnavailable
            CommonStatusCodes.CANCELED, STATUS_SIGN_IN_CANCELLED -> GatewayResult.Cancelled
            else -> GatewayResult.Failed("Google Identity code ${e.statusCode}")
        }
    } catch (e: IOException) {
        GatewayResult.NetworkUnavailable
    } catch (e: Exception) {
        GatewayResult.Failed(e.javaClass.simpleName)
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
