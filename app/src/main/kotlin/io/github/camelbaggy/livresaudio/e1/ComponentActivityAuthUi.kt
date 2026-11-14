package io.github.camelbaggy.livresaudio.e1

import android.accounts.AccountManager
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import io.github.camelbaggy.livresaudio.core.drive.auth.ActivityAuthUi
import kotlinx.coroutines.CompletableDeferred

/**
 * [ActivityAuthUi] backed by two launchers. Must be created in `onCreate` (before STARTED), as required by
 * `registerForActivityResult`. Limit of the E1 trial: an Activity recreation during a pending request cancels it.
 */
class ComponentActivityAuthUi(activity: ComponentActivity) : ActivityAuthUi {
    private var pendingAccount: CompletableDeferred<String?>? = null
    private var pendingConsent: CompletableDeferred<Intent?>? = null

    private val accountLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val email = if (it.resultCode == Activity.RESULT_OK) {
            it.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        } else {
            null
        }
        pendingAccount?.complete(email)
    }

    private val consentLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            pendingConsent?.complete(if (it.resultCode == Activity.RESULT_OK) it.data else null)
        }

    @Suppress("DEPRECATION")
    override suspend fun chooseGoogleAccount(): String? {
        val deferred = CompletableDeferred<String?>()
        pendingAccount?.complete(null)
        pendingAccount = deferred
        // System chooser limited to Google accounts, always shown (with "add an account"); needs no permission.
        val intent = AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null)
        accountLauncher.launch(intent)
        return deferred.await()
    }

    override suspend fun launchConsent(pendingIntent: PendingIntent): Intent? {
        val deferred = CompletableDeferred<Intent?>()
        pendingConsent?.complete(null)
        pendingConsent = deferred
        consentLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        return deferred.await()
    }
}
