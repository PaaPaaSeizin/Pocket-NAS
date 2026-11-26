package jp.pocketnas.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

class NetworkMonitor(
    context: Context,
    private val onChange: (connected: Boolean) -> Unit
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { onChange(true) }
        override fun onLost(network: Network) { onChange(false) }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) onChange(true)
        }
    }

    fun start() {
        try { cm.registerDefaultNetworkCallback(cb) } catch (_: Exception) {}
    }

    fun stop() {
        try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
    }
}