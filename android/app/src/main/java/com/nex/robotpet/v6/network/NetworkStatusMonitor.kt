package com.nex.robotpet.v6.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper

/**
 * Real-time Android internet connectivity observer (Part J).
 * Never fakes online status—checks actual validated internet capability from ConnectivityManager.
 */
class NetworkStatusMonitor(
    context: Context,
    private val onStatusChanged: (Boolean) -> Unit
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            notifyCurrentState()
        }

        override fun onLost(network: Network) {
            notifyCurrentState()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            notifyCurrentState()
        }
    }

    fun isCurrentlyOnline(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return hasInternet && isValidated
    }

    fun start() {
        if (registered) return
        notifyCurrentState()
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(req, callback)
            registered = true
        } catch (_: Exception) {
            notifyCurrentState()
        }
    }

    fun stop() {
        if (!registered) return
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
            // Safe cleanup
        } finally {
            registered = false
        }
    }

    private fun notifyCurrentState() {
        val online = isCurrentlyOnline()
        mainHandler.post { onStatusChanged(online) }
    }
}
