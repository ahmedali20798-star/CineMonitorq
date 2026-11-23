package com.canonmulticam.pro.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Binds process network sockets explicitly to Wi-Fi so Android One (e.g., Nokia 5.4)
 * does not reroute camera IP traffic to mobile 4G data when no internet is present.
 */
class WifiNetworkBinder(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var activeCallback: ConnectivityManager.NetworkCallback? = null

    fun bindWifi(onResult: (Boolean, String) -> Unit) {
        unbind()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val bound = connectivityManager.bindProcessToNetwork(network)
                onResult(bound, "Wi-Fi process binding -> $bound")
            }

            override fun onLost(network: Network) {
                connectivityManager.bindProcessToNetwork(null)
                onResult(false, "Wi-Fi network lost; binding cleared.")
            }
        }
        activeCallback = callback
        runCatching {
            connectivityManager.requestNetwork(request, callback)
        }.onFailure { err ->
            onResult(false, "Wi-Fi bind request failed: ${err.message}")
        }
    }

    fun unbind() {
        connectivityManager.bindProcessToNetwork(null)
        activeCallback?.let { cb ->
            runCatching { connectivityManager.unregisterNetworkCallback(cb) }
        }
        activeCallback = null
    }
}
