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
            startDiscovery() // Start discovery automatically on launch
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

        viewModelScope.launch {
            service.connectionStatus.collect { _connectionStatus.value = it }
        }
        viewModelScope.launch {
            service.discoveredDevices.collect { _discoveredDevices.value = it }
        }
        viewModelScope.launch {
            service.connectedDevice.collect { _connectedDevice.value = it }
        }
        viewModelScope.launch {
            service.pairingCode.collect { _pairingCode.value = it }
        }
        viewModelScope.launch {
            service.clipboardContent.collect { _clipboardContent.value = it }
        }
        viewModelScope.launch {
            service.incomingClipboard.collect { _incomingClipboard.value = it }
        }
        viewModelScope.launch {
            service.errorMessage.collect { _errorMessage.value = it }
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
        synapseService?.clearError()
    }

    // Target platform selection
    private val _targetPlatform = MutableStateFlow("macOS")
    val targetPlatform: StateFlow<String> = _targetPlatform.asStateFlow()

    fun setTargetPlatform(platform: String) {
        _targetPlatform.value = platform
        Log.d(TAG, "🎯 Target platform set to: $platform")
        // If iOS, we need to search for _synapse_ios._tcp services
        synapseService?.setTargetServiceType(
            if (platform == "iOS") "_synapse_ios._tcp." else "_synapse._tcp."
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
