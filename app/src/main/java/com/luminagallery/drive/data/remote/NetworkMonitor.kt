package com.luminagallery.drive.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.luminagallery.drive.domain.model.NetworkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors active network state via Android ConnectivityManager so the app can:
 *  - Enable full-resolution `index ± 1` pre-fetching & high-DPI thumbnails on unmetered Wi-Fi.
 *  - Restrict full-res loads strictly to on-demand and lower thumbnail size on metered cellular.
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkState = MutableStateFlow(computeCurrentState())
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _networkState.value = computeCurrentState()
        }

        override fun onLost(network: Network) {
            _networkState.value = computeCurrentState()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            _networkState.value = computeCurrentState()
        }
    }

    init {
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        }
    }

    fun isOnUnmeteredWifi(): Boolean = computeCurrentState().isUnmeteredWifi

    fun isOnline(): Boolean = computeCurrentState().isOnline

    private fun computeCurrentState(): NetworkState {
        val activeNetwork = connectivityManager.activeNetwork ?: return NetworkState(
            isOnline = false,
            isUnmeteredWifi = false
        )
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?: return NetworkState(isOnline = false, isUnmeteredWifi = false)

        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val isNotMetered =
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
                !connectivityManager.isActiveNetworkMetered
        val isWifiOrEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

        return NetworkState(
            isOnline = hasInternet,
            isUnmeteredWifi = hasInternet && (isNotMetered || isWifiOrEthernet && !connectivityManager.isActiveNetworkMetered)
        )
    }
}
