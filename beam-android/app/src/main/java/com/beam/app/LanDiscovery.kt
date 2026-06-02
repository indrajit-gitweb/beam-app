package com.beam.app

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class LanDiscovery(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discoveredServerUrl: String = ""

    companion object {
        const val TAG               = "LanDiscovery"
        const val SERVICE_TYPE      = "_beam-lan._tcp."
        const val ACTION_SERVER_FOUND = "com.beam.app.LAN_SERVER_FOUND"
    }

    fun startDiscovery() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "NSD discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Found service: ${serviceInfo.serviceName}")
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed: $errorCode")
                    }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        val port = info.port
                        val url  = "http://$host:$port"
                        discoveredServerUrl = url
                        Log.d(TAG, "Beam LAN server found: $url")
                        context.sendBroadcast(Intent(ACTION_SERVER_FOUND).apply {
                            putExtra("url", url)
                        })
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                discoveredServerUrl = ""
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NSD discovery", e)
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (e: Exception) { /* ignore */ }
        }
        discoveryListener = null
    }

    fun getServerUrl(): String = discoveredServerUrl
}
