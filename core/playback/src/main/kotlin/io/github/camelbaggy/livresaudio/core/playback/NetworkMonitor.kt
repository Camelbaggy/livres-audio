package io.github.camelbaggy.livresaudio.core.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** What the playback logic needs to know of the network (an interface so it can be faked in tests). */
interface NetworkStatus {
    /** Emits the current value first, then every change (distinct). */
    val online: Flow<Boolean>

    fun isOnlineNow(): Boolean

    /** True on a metered connection (mobile data): pre-caching is reduced there. */
    fun isMeteredNow(): Boolean
}

/** Whether the device has a usable (validated) network, as a flow. */
class NetworkMonitor(context: Context) : NetworkStatus {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    override fun isMeteredNow(): Boolean = manager.isActiveNetworkMetered

    override fun isOnlineNow(): Boolean {
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override val online: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isOnlineNow())
            }

            override fun onLost(network: Network) {
                trySend(isOnlineNow())
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(isOnlineNow())
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        trySend(isOnlineNow())
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
