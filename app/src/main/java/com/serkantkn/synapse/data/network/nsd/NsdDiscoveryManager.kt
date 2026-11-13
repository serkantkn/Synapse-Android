package com.serkantkn.synapse.data.network.nsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.serkantkn.synapse.domain.model.Device
import com.serkantkn.synapse.domain.repository.DiscoveryRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * NsdDiscoveryManager with resolve queue and WifiLock for Samsung compatibility.
 */
class NsdDiscoveryManager(private val context: Context) : DiscoveryRepository {

    companion object {
        private const val TAG = "NsdDiscovery"
        const val SERVICE_NAME = "Synapse"
    }

    var serviceType = "_synapse._tcp."

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val deviceId: String = getOrCreateDeviceId()
    private val handler = Handler(Looper.getMainLooper())

    // Queue to handle service resolutions sequentially
    private val resolveQueue = LinkedBlockingQueue<NsdServiceInfo>()
    private var isResolving = false

    private fun getOrCreateDeviceId(): String {
        val prefs = context.getSharedPreferences("synapse_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        return id!!
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("SynapseNSD").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "MulticastLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire MulticastLock", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "SynapseWiFi"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "WifiLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WifiLock", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d(TAG, "MulticastLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release MulticastLock", e)
        }
        multicastLock = null
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.d(TAG, "WifiLock released")
            }
        } catch (_: Exception) {}
        wifiLock = null
    }

    override fun registerService(port: Int) {
        unregisterService()
        acquireMulticastLock()
        acquireWifiLock()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = this@NsdDiscoveryManager.serviceType
            setPort(port)
            setAttribute("device_id", deviceId)
            setAttribute("device_type", "android")
            setAttribute("device_name", android.os.Build.MODEL)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "✅ Service registered: ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "❌ Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    override fun unregisterService() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {}
        }
        registrationListener = null
        releaseMulticastLock()
        releaseWifiLock()
    }

    override fun discoverDevices(): Flow<List<Device>> = callbackFlow {
        val devices = mutableMapOf<String, Device>()
        acquireMulticastLock()
        acquireWifiLock()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "🔍 Discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "🔍 Found: ${serviceInfo.serviceName}")
                resolveQueue.add(serviceInfo)
                processNextInResolveQueue(devices) { trySend(it) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val lostId = serviceInfo.attributes["device_id"]?.let { String(it) }
                if (lostId != null) {
                    devices.remove(lostId)
                    trySend(devices.values.toList())
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceTypeStarted: String, errorCode: Int) {
                Log.e(TAG, "❌ Discovery start failed: $errorCode")
                // Retry after delay
                handler.postDelayed({
                    try {
                        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                    } catch (_: Exception) {}
                }, 3000)
            }
            override fun onStopDiscoveryFailed(serviceTypeStarted: String, errorCode: Int) {}
        }

        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
        }

        awaitClose {
            stopDiscovery()
        }
    }

    private fun processNextInResolveQueue(devices: MutableMap<String, Device>, onUpdate: (List<Device>) -> Unit) {
        if (isResolving || resolveQueue.isEmpty()) return

        isResolving = true
        val info = resolveQueue.poll() ?: run {
            isResolving = false
            return
        }

        resolveServiceWithRetry(info, devices, onUpdate, attempt = 0)
    }

    private fun resolveServiceWithRetry(
        info: NsdServiceInfo,
        devices: MutableMap<String, Device>,
        onUpdate: (List<Device>) -> Unit,
        attempt: Int
    ) {
        nsdManager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "❌ Resolve failed (attempt ${attempt + 1}): $errorCode")
                if (attempt < 2) {
                    // Retry after short delay
                    handler.postDelayed({
                        resolveServiceWithRetry(info, devices, onUpdate, attempt + 1)
                    }, 500L * (attempt + 1))
                } else {
                    isResolving = false
                    processNextInResolveQueue(devices, onUpdate)
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val remoteDeviceId = serviceInfo.attributes["device_id"]?.let { String(it) } ?: UUID.randomUUID().toString()
                val host = serviceInfo.host
                val address = host?.hostAddress ?: ""
                
                Log.d(TAG, "🔍 Resolved service: ${serviceInfo.serviceName} -> Host: ${host?.hostName}, IP: $address")

                if (remoteDeviceId != deviceId && address.isNotEmpty()) {
                    val device = Device(
                        id = remoteDeviceId,
                        name = serviceInfo.attributes["device_name"]?.let { String(it) } ?: serviceInfo.serviceName,
                        deviceType = serviceInfo.attributes["device_type"]?.let { String(it) } ?: "unknown",
                        ipAddress = address,
                        port = serviceInfo.port
                    )
                    devices[device.id] = device
                    onUpdate(devices.values.toList())
                    Log.d(TAG, "✅ Device Added: ${device.name} at ${device.ipAddress}:${device.port}")
                } else {
                    Log.w(TAG, "⚠️ Resolved device ignored: ID matches local or IP is empty ($address)")
                }

                isResolving = false
                processNextInResolveQueue(devices, onUpdate)
            }
        })
    }

    override fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {}
        }
        discoveryListener = null
        resolveQueue.clear()
        isResolving = false
        releaseMulticastLock()
        releaseWifiLock()
    }

    fun getDeviceId(): String = deviceId
}
