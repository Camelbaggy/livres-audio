package io.github.camelbaggy.livresaudio.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import io.github.camelbaggy.livresaudio.R

private const val PREFS = "ui_prefs"
private const val KEY_ASKED = "notification_permission_asked"

private fun needsAsking(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    return !granted && !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)
}

/**
 * Returns a function to wrap "start a playback": the first time (Android 13+, permission missing) it explains why
 * notifications are needed and asks for `POST_NOTIFICATIONS`; whatever the answer, the playback then starts.
 */
@Composable
fun rememberPlaybackGate(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var explain by remember { mutableStateOf(false) }

    fun markAsked() =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()

    fun proceed() {
        explain = false
        pending?.invoke()
        pending = null
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { proceed() }
    if (explain) {
        AlertDialog(
            onDismissRequest = {
                markAsked()
                proceed()
            },
            title = { Text(stringResource(R.string.notif_title)) },
            text = { Text(stringResource(R.string.notif_message)) },
            confirmButton = {
                TextButton(onClick = {
                    markAsked()
                    explain = false
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text(stringResource(R.string.notif_allow)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    markAsked()
                    proceed()
                }) { Text(stringResource(R.string.notif_later)) }
            }
        )
    }
    return remember(context) {
        { action: () -> Unit ->
            if (needsAsking(context)) {
                pending = action
                explain = true
            } else {
                action()
            }
        }
    }
}
