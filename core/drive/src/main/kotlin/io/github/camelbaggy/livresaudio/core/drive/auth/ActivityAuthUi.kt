package io.github.camelbaggy.livresaudio.core.drive.auth

import android.app.PendingIntent
import android.content.Intent
import io.github.camelbaggy.livresaudio.core.model.AuthUi

/** Android implementation of [AuthUi]: the Activity provides the launchers (implemented in `app`). */
interface ActivityAuthUi : AuthUi {
    /**
     * Forced Google account chooser (the system picker, including "add an account").
     * Returns the chosen address, or null if the user cancelled.
     */
    suspend fun chooseGoogleAccount(): String?

    /**
     * Launches the consent screen ([PendingIntent] returned by `AuthorizationClient`) and returns the
     * result intent, or null if the user cancelled.
     */
    suspend fun launchConsent(pendingIntent: PendingIntent): Intent?
}
