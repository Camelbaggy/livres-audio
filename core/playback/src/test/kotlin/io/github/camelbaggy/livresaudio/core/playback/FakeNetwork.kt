package io.github.camelbaggy.livresaudio.core.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A scriptable [NetworkStatus]. */
class FakeNetwork(online: Boolean = true, var metered: Boolean = false) : NetworkStatus {
    private val state = MutableStateFlow(online)

    override val online: Flow<Boolean> = state

    fun set(online: Boolean) {
        state.value = online
    }

    override fun isOnlineNow(): Boolean = state.value

    override fun isMeteredNow(): Boolean = metered
}
