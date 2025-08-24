package com.example.celsocam.util

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Descubre servicios mDNS/DNS-SD tipo "_celsocam._tcp." en la LAN
 * y resuelve host/puerto para construir la URL del WS.
 * - Toma un Wifi MulticastLock para asegurar recepción de mDNS.
 * - Loguea eventos para debug.
 */
class NsdHelper(
    context: Context,
    private val serviceType: String = "_celsocam._tcp.",
    private val onResolved: (host: String, port: Int, name: String) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private val tag = "NsdHelper"
    private val appCtx = context.applicationContext
    private val nsd: NsdManager = appCtx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())

    private val wifi: WifiManager = appCtx.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var mlock: WifiManager.MulticastLock? = null

    private var discovering = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (discovering) return
        try {
            if (mlock == null) {
                mlock = wifi.createMulticastLock("celsocam-mdns").apply { setReferenceCounted(false) }
            }
            mlock?.acquire()
            Log.i(tag, "MulticastLock ACQUIRED")
        } catch (e: Exception) {
            Log.w(tag, "No MulticastLock: ${e.message}")
        }

        val dl = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(tag, "Discovery STARTED for type=$regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val t = serviceInfo.serviceType ?: ""
                val n = serviceInfo.serviceName ?: ""
                Log.i(tag, "Service FOUND: name=$n type=$t")

                // Aceptamos coincidencias que contengan "_celsocam._tcp"
                if (!t.contains("_celsocam._tcp")) return

                try {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.w(tag, "Resolve FAILED code=$errorCode name=${serviceInfo.serviceName}")
                            main.post { onError("resolveFailed($errorCode)") }
                        }

                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val host = resolved.host?.hostAddress ?: run {
                                Log.w(tag, "Resolve NO HOST for ${resolved.serviceName}")
                                return
                            }
                            val port = resolved.port
                            val name = resolved.serviceName ?: "Celsocam"
                            Log.i(tag, "Service RESOLVED: $name -> $host:$port")
                            main.post { onResolved(host, port, name) }
                        }
                    })
                } catch (e: Exception) {
                    Log.e(tag, "Resolve EXCEPTION: ${e.message}")
                    main.post { onError("resolveException: ${e.message}") }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(tag, "Service LOST: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(tag, "Discovery STOPPED: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(tag, "StartDiscovery FAILED: $serviceType code=$errorCode")
                stop()
                main.post { onError("startDiscoveryFailed($errorCode)") }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(tag, "StopDiscovery FAILED: $serviceType code=$errorCode")
                stop()
                main.post { onError("stopDiscoveryFailed($errorCode)") }
            }
        }
        discoveryListener = dl
        try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, dl)
            discovering = true
        } catch (e: Exception) {
            Log.e(tag, "discover EXCEPTION: ${e.message}")
            onError("discoverException: ${e.message}")
        }
    }

    fun stop() {
        if (!discovering) {
            releaseLock()
            return
        }
        discoveryListener?.let {
            try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        discoveryListener = null
        discovering = false
        Log.i(tag, "Discovery STOP requested")
        releaseLock()
    }

    private fun releaseLock() {
        try {
            if (mlock?.isHeld == true) {
                mlock?.release()
                Log.i(tag, "MulticastLock RELEASED")
            }
        } catch (_: Exception) {}
    }
}
