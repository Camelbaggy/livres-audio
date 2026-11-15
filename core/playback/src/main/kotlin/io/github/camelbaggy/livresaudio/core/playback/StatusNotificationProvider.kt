package io.github.camelbaggy.livresaudio.core.playback

import android.content.Context
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider

/**
 * The standard Media3 notification whose second line (normally the author) shows the stream state while there is
 * one to show ("En attente du réseau… — 12 min disponibles", "Fichier introuvable"). [statusLine] is read on
 * the main thread each time the notification is built; the service calls `triggerNotificationUpdate()` when it
 * changes.
 */
@UnstableApi
class StatusNotificationProvider(context: Context, private val statusLine: () -> String?) :
    DefaultMediaNotificationProvider(context) {
    public override fun getNotificationContentText(metadata: MediaMetadata): CharSequence? =
        statusLine() ?: super.getNotificationContentText(metadata)
}
