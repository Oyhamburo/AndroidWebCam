package com.example.celsocam.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build

/**
 * Fuerza a que las conexiones salientes se hagan por la red Wi-Fi si está disponible.
 * Útil cuando hay VPN/WARP/“Secure Wi-Fi” que secuestran el tráfico (100.64/10/100.95.x.x).
 */
class NetworkBinder(private val context: Context) {

    private var boundNetwork: Network? = null

    /**
     * Intenta encontrar una red con TRANSPORT_WIFI y la bindea para el proceso.
     * @return true si se bindeó a Wi-Fi, false si no había Wi-Fi disponible.
     */
    fun bindWifiIfPresent(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val networks = cm.allNetworks
        val wifi = networks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cm.bindProcessToNetwork(wifi)
        } else {
            @Suppress("DEPRECATION")
            ConnectivityManager.setProcessDefaultNetwork(wifi)
        }
        boundNetwork = wifi
        return true
    }

    /** Quita el bind y vuelve al ruteo por defecto. */
    fun unbind() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            cm.bindProcessToNetwork(null)
        } else {
            @Suppress("DEPRECATION")
            ConnectivityManager.setProcessDefaultNetwork(null)
        }
        boundNetwork = null
    }
}
