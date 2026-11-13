package com.serkantkn.synapse.presentation.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.serkantkn.synapse.data.service.SynapseService
import com.serkantkn.synapse.domain.model.ConnectionStatus
import com.serkantkn.synapse.domain.model.Device
import com.serkantkn.synapse.domain.model.Packet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel that acts as a thin layer between UI and SynapseService.
 * All heavy network/crypto logic lives in the service, which survives activity closure.
 */
class SynapseViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SynapseVM"
    }

    // Service binding
    private var synapseService: SynapseService? = null
    private var isBound = false

    // State (mirrored from service for UI)
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<Device>>(emptyList())
    val discoveredDevices: StateFlow<List<Device>> = _discoveredDevices.asStateFlow()

    private val _connectedDevice = MutableStateFlow<Device?>(null)
    val connectedDevice: StateFlow<Device?> = _connectedDevice.asStateFlow()

    private val _pairingCode = MutableStateFlow("")
    val pairingCode: StateFlow<String> = _pairingCode.asStateFlow()

    private val _clipboardContent = MutableStateFlow("")
    val clipboardContent: StateFlow<String> = _clipboardContent.asStateFlow()

    private val _incomingClipboard = MutableStateFlow<String?>(null)
    val incomingClipboard: StateFlow<String?> = _incomingClipboard.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SynapseService.SynapseBinder
            synapseService = binder.getService()
            isBound = true
            Log.d(TAG, "✅ Bound to SynapseService")
            // No search before a platform is picked: a paired Mac nearby would otherwise be
            // reconnected while the selection screen is still open, even if the user picks iOS.
            if (platformChosen && !demo) selectPlatformOnService()
            startCollectingServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synapseService = null
            isBound = false
            Log.d(TAG, "❌ Disconnected from SynapseService")
        }
    }

    init {
        // Start and bind to the foreground service
        val context = getApplication<Application>()
        val intent = Intent(context, SynapseService::class.java)
        context.startForegroundService(intent)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Collect state flows from the service and mirror to the ViewModel.
     */
    private fun startCollectingServiceState() {
        val service = synapseService ?: return

        // In demo mode the fake state stays on screen; the service must not overwrite it.
        viewModelScope.launch {
            service.connectionStatus.collect { if (!demo) _connectionStatus.value = it }
        }
        viewModelScope.launch {
            service.discoveredDevices.collect { if (!demo) _discoveredDevices.value = it }
        }
        viewModelScope.launch {
            service.connectedDevice.collect { if (!demo) _connectedDevice.value = it }
        }
        viewModelScope.launch {
            service.pairingCode.collect { _pairingCode.value = it }
        }
        viewModelScope.launch {
            service.clipboardContent.collect { if (!demo) _clipboardContent.value = it }
        }
        viewModelScope.launch {
            service.incomingClipboard.collect { if (!demo) _incomingClipboard.value = it }
        }
        viewModelScope.launch {
            service.errorMessage.collect { if (!demo) _errorMessage.value = it }
        }
    }

    private var demo = false

    /**
     * Fake state for UI screenshots; MainActivity only calls this in debuggable builds.
     * "connected": paired with a Mac. "devices": searching, two devices found.
     */
    fun startDemo(mode: String) {
        demo = true
        val mac = Device(id = "demo-mac", name = "MacBook Air", deviceType = "macOS", ipAddress = "192.168.1.14", port = 9876)
        when (mode) {
            "connected" -> {
                _connectedDevice.value = mac
                _clipboardContent.value = "https://synapse.example/davet/8f2c"
                _incomingClipboard.value = "Kapı kodu: 4821#"
                _connectionStatus.value = ConnectionStatus.CONNECTED
            }
            "devices" -> {
                _discoveredDevices.value = listOf(
                    mac,
                    Device(id = "demo-ios", name = "Serkan'ın iPhone'u", deviceType = "iOS", ipAddress = "192.168.1.15", port = 55673)
                )
                _connectionStatus.value = ConnectionStatus.DISCOVERING
            }
            "error" -> {
                _errorMessage.value = "PIN kodu yanlış. Mac'te görünen 6 haneli kodu tekrar gir."
                _connectionStatus.value = ConnectionStatus.ERROR
            }
        }
    }

    // MARK: - Actions (delegated to service)

    fun startDiscovery() {
        synapseService?.startDiscovery()
    }

    fun stopDiscovery() {
        synapseService?.stopDiscovery()
    }

    fun pairWithDevice(device: Device) {
        synapseService?.pairWithDevice(device)
    }

    fun pairWithPin(pin: String) {
        synapseService?.pairWithPin(pin)
    }

    fun pairWithIp(ip: String, pin: String) {
        synapseService?.pairWithIp(ip, pin)
    }

    fun rejectPairing() {
        synapseService?.rejectPairing()
    }

    fun disconnect() {
        synapseService?.disconnect()
    }

    fun getQrCodeData(): String {
        return synapseService?.getQrCodeData() ?: ""
    }

    fun setPairingCode(code: String) {
        synapseService?.setPairingCode(code)
    }

    fun clearError() {
        if (demo) _errorMessage.value = null
        synapseService?.clearError()
    }

    // Target platform selection
    private val _targetPlatform = MutableStateFlow("macOS")
    val targetPlatform: StateFlow<String> = _targetPlatform.asStateFlow()

    /** Set once the user picks a platform; discovery (and auto-reconnect) waits for it. */
    private var platformChosen = false

    fun setTargetPlatform(platform: String) {
        _targetPlatform.value = platform
        platformChosen = true
        Log.d(TAG, "🎯 Target platform set to: $platform")
        if (!demo) selectPlatformOnService() // a bind that's still pending does it on connect
    }

    private fun selectPlatformOnService() {
        // iOS advertises _synapse_ios._tcp, the Mac _synapse._tcp.
        synapseService?.selectTargetServiceType(
            if (_targetPlatform.value == "iOS") "_synapse_ios._tcp." else "_synapse._tcp."
        )
    }

    override fun onCleared() {
        super.onCleared()
        if (isBound) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding service", e)
            }
            isBound = false
        }
        // Note: We do NOT stop the service here. It keeps running in the background.
    }
}
