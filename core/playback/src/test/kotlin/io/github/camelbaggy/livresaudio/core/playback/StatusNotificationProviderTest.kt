package io.github.camelbaggy.livresaudio.core.playback

import android.content.Context
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class StatusNotificationProviderTest {
    private val metadata = MediaMetadata.Builder().setTitle("Titre").setArtist("Auteur").build()

    @Test
    fun `the status replaces the author line while there is one, then the author comes back`() {
        var status: String? = "En attente du réseau… — 12 min disponibles"
        val provider = StatusNotificationProvider(ApplicationProvider.getApplicationContext<Context>()) { status }
        assertEquals("En attente du réseau… — 12 min disponibles", provider.getNotificationContentText(metadata))
        status = null
        assertEquals("Auteur", provider.getNotificationContentText(metadata))
    }
}
