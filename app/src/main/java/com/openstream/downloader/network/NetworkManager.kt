package com.openstream.downloader.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicReference

object NetworkManager {
    private const val TAG = "NetworkManager"
    private var connectivityManager: ConnectivityManager? = null
    private val activeCellularNetwork = AtomicReference<Network?>(null)

    fun init(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        connectivityManager = cm
        if (cm == null) return

        // 1. Initial scan of existing connected networks
        updateActiveCellular(cm)

        // 2. Request cellular network to stay alive and listen for changes
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()

            cm.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Cellular network available: $network")
                    activeCellularNetwork.set(network)
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "Cellular network lost: $network")
                    if (activeCellularNetwork.get() == network) {
                        activeCellularNetwork.set(null)
                    }
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    ) {
                        activeCellularNetwork.set(network)
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register cellular NetworkRequest: ${e.message}")
        }
    }

    private fun updateActiveCellular(cm: ConnectivityManager) {
        try {
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    activeCellularNetwork.set(network)
                    Log.d(TAG, "Found existing active cellular network: $network")
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error updating active cellular: ${e.message}")
        }
    }

    fun getCellularNetwork(): Network? {
        val current = activeCellularNetwork.get()
        if (current != null) return current
        connectivityManager?.let { updateActiveCellular(it) }
        return activeCellularNetwork.get()
    }

    fun getCellularOkHttpClient(baseClient: OkHttpClient): OkHttpClient? {
        val cellular = getCellularNetwork() ?: return null
        return try {
            baseClient.newBuilder()
                .socketFactory(cellular.socketFactory)
                .dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String): List<java.net.InetAddress> {
                        return cellular.getAllByName(hostname).toList()
                    }
                })
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create cellular OkHttpClient: ${e.message}")
            null
        }
    }
}
