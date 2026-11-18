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
 * - Never fakes online status: checks active network capabilities from ConnectivityManager.
 * - Distinguishes [hasNetworkTransport] (active Wi-Fi/Cellular/Ethernet with INTERNET capability,
 *   used before attempting outbound HTTPS requests so hotspots with delayed OS captive-portal probes
 *   are not falsely blocked) from [isCurrentlyOnline] (OS-validated OR live-HTTPS-confirmed on the
 *   current active network).
 */
class NetworkStatusMonitor(
    context: Context,
    private val onStatusChanged: (Boolean) -> Unit
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var registered = false

    @Volatile
    private var httpsConfirmedNetwork: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            notifyCurrentState()
        }

        override fun onLost(network: Network) {
            if (httpsConfirmedNetwork == network) {
                httpsConfirmedNetwork = null
            }
            notifyCurrentState()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            notifyCurrentState()
        }
    }

    /**
     * True when the device has an active network interface declaring internet capability.
     */
    fun hasNetworkTransport(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * True when internet is validated by Android OS OR verified via a real HTTPS response
     * on the currently active network. Immediately returns false if the network disconnects.
     */
    fun isCurrentlyOnline(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return isValidated || (httpsConfirmedNetwork == activeNetwork)
    }

    /**
     * Record that a real HTTPS request succeeded over the current active network.
     */
    fun confirmHttpsReachable() {
        val activeNetwork = connectivityManager.activeNetwork ?: return
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            httpsConfirmedNetwork = activeNetwork
            notifyCurrentState()
        }
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
