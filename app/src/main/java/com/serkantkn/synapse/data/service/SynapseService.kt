package com.serkantkn.synapse.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.Matrix
import androidx.core.app.NotificationCompat
import com.serkantkn.synapse.MainActivity
import com.serkantkn.synapse.R
import com.serkantkn.synapse.core.util.generatePairingCode
import com.serkantkn.synapse.data.network.crypto.CryptoManager
import com.serkantkn.synapse.data.network.nsd.NsdDiscoveryManager
import com.serkantkn.synapse.data.network.socket.TcpTransferManager
import com.serkantkn.synapse.domain.model.ConnectionStatus
import com.serkantkn.synapse.domain.model.Device
import com.serkantkn.synapse.domain.model.Packet
import com.serkantkn.synapse.domain.model.PacketType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.util.UUID
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.ImageReader
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image

/**
 * Foreground Service that keeps Synapse running even when the app UI is closed.
 * Manages discovery, TCP connection, clipboard monitoring, and heartbeat.
 */
class SynapseService : Service() {

    companion object {
        private const val TAG = "SynapseService"
        private const val CHANNEL_ID = "synapse_channel"
        private const val NOTIFICATION_ID = 1001
        private const val CLIPBOARD_POLL_INTERVAL = 1000L
        private const val HEARTBEAT_INTERVAL = 5000L // 5 seconds
        private const val HEARTBEAT_TIMEOUT = 18000L // 18 seconds (be more lenient)
        private const val PREFS_NAME = "synapse_prefs"
        private const val KEY_KNOWN_DEVICES = "known_devices"
        private const val KEY_TOKEN_PREFIX = "pairing_token_"
        private const val PROTOCOL_VERSION = 2
        private const val MAX_RECONNECT_ATTEMPTS = 6
    }

    // Binder for Activity <-> Service communication
    private val binder = SynapseBinder()

    inner class SynapseBinder : Binder() {
        fun getService(): SynapseService = this@SynapseService
    }

    /**
     * Called by MainActivity when MediaProjection permission is granted.
     */
    fun onMediaProjectionGranted(resultCode: Int, data: Intent) {
        // Update foreground service type to include mediaProjection BEFORE getting the projection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification("Ekran yansıtılıyor..."),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        }

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)
        
        if (mediaProjection != null) {
            // Android 14+ requires registering a callback before starting the capture
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // Projection ended (by us or by the user via system UI): it can't be reused.
                    onProjectionStopped()
                }
            }, Handler(Looper.getMainLooper()))

            setupScreenCapture()
            isStreaming.value = true
            Log.d(TAG, "✅ MediaProjection started")
        } else {
            Log.e(TAG, "❌ MediaProjection failed")
        }
    }

    // Managers
    private lateinit var discoveryManager: NsdDiscoveryManager
    private val transferManager = TcpTransferManager()

    // Telephony Receivers
    private var callReceiver: CallReceiver? = null
    private var smsReceiver: SmsReceiver? = null

    // Coroutine scope tied to the service lifecycle
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // State (exposed to ViewModel via binding)
    val connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val discoveredDevices = MutableStateFlow<List<Device>>(emptyList())
    val connectedDevice = MutableStateFlow<Device?>(null)
    val pairingCode = MutableStateFlow(generatePairingCode())
    val clipboardContent = MutableStateFlow("")
    val incomingClipboard = MutableStateFlow<String?>(null)
    val errorMessage = MutableStateFlow<String?>(null)
    val isStreaming = MutableStateFlow(false)

    // Screen Mirroring
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenStreamJob: Job? = null

    // Crypto
    var keyPair: KeyPair? = null
        private set

    // Clipboard monitoring
    private lateinit var clipboardManager: ClipboardManager
    private var lastClipboardText: String = ""
    private val clipboardHandler = Handler(Looper.getMainLooper())
    private val clipboardRunnable = object : Runnable {
        override fun run() {
            // Deprecated: Aggressive 1-second polling replaced by event-driven OnPrimaryClipChangedListener
        }
    }

    private var serverPort: Int = 0
    private var heartbeatJob: Job? = null
    private var discoveryJob: Job? = null
    private var lastConnectedDeviceId: String? = null
    
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private fun getKnownDevices(): Set<String> {
        return prefs.getStringSet(KEY_KNOWN_DEVICES, emptySet()) ?: emptySet()
    }

    private fun saveKnownDevice(deviceId: String) {
        val known = getKnownDevices().toMutableSet()
        known.add(deviceId)
        prefs.edit().putStringSet(KEY_KNOWN_DEVICES, known).apply()
        Log.d(TAG, "💾 Device $deviceId saved to known devices")
    }

    private fun isKnownDevice(deviceId: String): Boolean {
        return getKnownDevices().contains(deviceId)
    }

    // Pairing token per peer, derived from the first PIN-authenticated session. Reconnects prove
    // knowledge of it instead of a PIN; it also feeds the session key, so an impostor using the
    // same device id can't derive the key.
    private fun getPairingToken(deviceId: String): String? = prefs.getString("$KEY_TOKEN_PREFIX$deviceId", null)

    private fun savePairingToken(deviceId: String, token: String) {
        prefs.edit().putString("$KEY_TOKEN_PREFIX$deviceId", token).apply()
    }

    private fun removePairingToken(deviceId: String) {
        prefs.edit().remove("$KEY_TOKEN_PREFIX$deviceId").apply()
    }

    // Manual-IP / QR targets have placeholder ids, so remember which peer id answered at an address.
    private fun peerIdForAddress(ip: String, port: Int): String? = prefs.getString("peer_at_$ip:$port", null)

    private fun savePeerIdForAddress(ip: String, port: Int, peerId: String) {
        prefs.edit().putString("peer_at_$ip:$port", peerId).apply()
    }

    /** Auth mode + secret of the handshake in flight; read on the socket reader thread. */
    private data class PendingAuth(val mode: String, val secret: ByteArray, val targetId: String)

    @Volatile
    private var pendingAuth: PendingAuth? = null
    private var reconnectAttempts = 0
    private var wakeLock: PowerManager.WakeLock? = null

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        checkClipboardChange()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        discoveryManager = NsdDiscoveryManager(this)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        keyPair = CryptoManager.generateKeyPair()
        
        transferManager.onDisconnect = {
            // Invoked on the socket reader thread: hop to the main thread before touching state.
            serviceScope.launch {
                Log.d(TAG, "🔌 Connection lost detected by TCP manager")
                // Save the device ID before cleaning up, so we can auto-reconnect
                lastConnectedDeviceId = connectedDevice.value?.id
                disconnect()
                // Auto-restart discovery (will auto-reconnect if device is found)
                startDiscovery()
            }
        }

        // Derive the session key on the reader thread as soon as HANDSHAKE_RESPONSE arrives,
        // so an encrypted frame sent right after the response is decrypted correctly.
        transferManager.handshakeKeyProvider = { packet ->
            val theirPublicKeyStr = packet.payload["public_key"] as? String
            val pair = keyPair
            val auth = pendingAuth
            if (packet.payload["status"] == "ACCEPTED" && !theirPublicKeyStr.isNullOrEmpty() && pair != null && auth != null) {
                try {
                    CryptoManager.deriveSharedSecret(pair.private, CryptoManager.decodePublicKey(theirPublicKeyStr), auth.secret)
                } catch (e: Exception) {
                    Log.e(TAG, "Key derivation failed", e)
                    null
                }
            } else null
        }
        
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                createNotification("Synapse hazır — bağlantı bekleniyor"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Synapse hazır — bağlantı bekleniyor"))
        }
        
        // Register clipboard listener for real-time sync
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        
        setupTelephonyReceivers()
        listenForIncomingPackets()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Synapse::SyncWakeLock").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L) // 10 min max, renewed by heartbeat
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "WakeLock released")
        }
        wakeLock = null
    }

    private fun setupTelephonyReceivers() {
        callReceiver = CallReceiver { state, number ->
            sendCallEvent(state, number)
        }
        smsReceiver = SmsReceiver { sender, body ->
            sendSmsEvent(sender, body)
        }
        
        registerReceiver(callReceiver, android.content.IntentFilter(android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED))
        registerReceiver(smsReceiver, android.content.IntentFilter("android.provider.Telephony.SMS_RECEIVED"))
    }

    private fun sendCallEvent(state: String, number: String?) {
        serviceScope.launch {
            val payload = mapOf(
                "state" to state,
                "number" to (number ?: "Gizli Numara")
            )
            val packet = Packet(
                packetId = UUID.randomUUID().toString(),
                type = PacketType.CALL_EVENT,
                senderId = discoveryManager.getDeviceId(),
                timestamp = System.currentTimeMillis(),
                payload = payload
            )
            transferManager.sendPacket(packet)
        }
    }

    private fun sendSmsEvent(sender: String, body: String) {
        serviceScope.launch {
            val payload = mapOf(
                "sender" to sender,
                "body" to body
            )
            val packet = Packet(
                packetId = UUID.randomUUID().toString(),
                type = PacketType.SMS_EVENT,
                senderId = discoveryManager.getDeviceId(),
                timestamp = System.currentTimeMillis(),
                payload = payload
            )
            transferManager.sendPacket(packet)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand (action: ${intent?.action})")
        if (intent?.action == "ACTION_DISCONNECT") {
            disconnect()
            startDiscovery()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        callReceiver?.let { unregisterReceiver(it) }
        smsReceiver?.let { unregisterReceiver(it) }
        stopHeartbeat()
        releaseWakeLock()
        clipboardHandler.removeCallbacks(clipboardRunnable)
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        discoveryManager.stopDiscovery()
        discoveryManager.unregisterService()
        transferManager.stopServer()
        serviceScope.cancel()
        super.onDestroy()
    }

    // MARK: - Notification

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Synapse Bağlantı Servisi",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Cihazlar arası senkronizasyon servisi"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Disconnect action
        val disconnectIntent = Intent(this, SynapseService::class.java).apply {
            action = "ACTION_DISCONNECT"
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Synapse")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (connectionStatus.value == ConnectionStatus.CONNECTED) {
            builder.addAction(R.mipmap.ic_launcher, "Bağlantıyı Kes", disconnectPendingIntent)
        }

        return builder.build()
    }



    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    // MARK: - Discovery

    fun setTargetServiceType(type: String) {
        discoveryManager.serviceType = type
    }

    fun startDiscovery() {
        serviceScope.launch {
            try {
                connectionStatus.value = ConnectionStatus.DISCOVERING
                updateNotification("Cihaz aranıyor...")

                // The phone is always the client (Mac and iOS listen). Not running an inbound
                // server or advertising one removes an unauthenticated entry point into the phone.

                // Only one discovery at a time: cancelling the old flow stops its NSD listener
                // *before* the new one is registered (stopDiscovery acts on a shared field).
                discoveryJob?.cancelAndJoin()
                discoveryJob = launch {
                    discoveryManager.discoverDevices().collect { devices ->
                        discoveredDevices.value = devices
                        
                        // Auto-reconnect only to peers we hold a pairing token for (no PIN needed).
                        if (connectionStatus.value == ConnectionStatus.DISCOVERING) {
                            val matchingDevice = devices.find { getPairingToken(it.id) != null }
                            if (matchingDevice != null) {
                                Log.d(TAG, "♻️ Auto-reconnecting to ${matchingDevice.name}...")
                                lastConnectedDeviceId = null // prevent retry loop
                                pairWithDevice(matchingDevice)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Discovery error", e)
                errorMessage.value = "Keşif hatası: ${e.message}"
                connectionStatus.value = ConnectionStatus.ERROR
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        discoveryManager.stopDiscovery()
        discoveryManager.unregisterService()
        if (connectionStatus.value == ConnectionStatus.DISCOVERING) {
            connectionStatus.value = ConnectionStatus.DISCONNECTED
            updateNotification("Synapse hazır — bağlantı bekleniyor")
        }
    }

    // MARK: - Pairing

    fun pairWithDevice(device: Device) {
        serviceScope.launch {
            try {
                connectionStatus.value = ConnectionStatus.PAIRING
                errorMessage.value = null
                updateNotification("Eşleşme: ${device.name}...")

                // Known peer → prove the stored token; otherwise prove the PIN shown on the peer.
                val peerId = if (getPairingToken(device.id) != null) device.id
                    else peerIdForAddress(device.ipAddress, device.port) ?: device.id
                val token = getPairingToken(peerId)
                val auth = if (token != null) {
                    PendingAuth(CryptoManager.AUTH_MODE_TOKEN, java.util.Base64.getDecoder().decode(token), peerId)
                } else {
                    val pin = pairingCode.value.trim()
                    if (pin.length < 6) {
                        errorMessage.value = "Karşı cihazda görünen PIN kodunu girin"
                        connectionStatus.value = ConnectionStatus.ERROR
                        return@launch
                    }
                    PendingAuth(CryptoManager.AUTH_MODE_PIN, pin.toByteArray(Charsets.UTF_8), device.id)
                }

                // Fresh ephemeral key pair per session (forward secrecy across sessions).
                val pair = CryptoManager.generateKeyPair()
                keyPair = pair
                pendingAuth = auth

                transferManager.connectTo(device.ipAddress, device.port)

                val publicKeyStr = CryptoManager.encodePublicKey(pair.public)
                val handshakePacket = Packet(
                    packetId = UUID.randomUUID().toString(),
                    type = PacketType.HANDSHAKE_REQUEST,
                    senderId = discoveryManager.getDeviceId(),
                    timestamp = System.currentTimeMillis(),
                    payload = mapOf(
                        "device_name" to android.os.Build.MODEL,
                        "device_type" to "android",
                        "public_key" to publicKeyStr,
                        "protocol" to PROTOCOL_VERSION,
                        "auth_mode" to auth.mode,
                        // The PIN itself is never sent; only an HMAC bound to our ephemeral key.
                        "auth_proof" to CryptoManager.authProof(auth.secret, auth.mode, publicKeyStr)
                    )
                )
                transferManager.sendPacket(handshakePacket, encrypt = false)
                connectedDevice.value = device

            } catch (e: Exception) {
                Log.e(TAG, "Pairing error: ${e.message}")
                // A paired peer that's briefly unreachable (restarting, stale NSD record):
                // keep retrying with backoff instead of parking in ERROR.
                if (pendingAuth?.mode == CryptoManager.AUTH_MODE_TOKEN && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    reconnectAttempts++
                    pendingAuth = null
                    connectionStatus.value = ConnectionStatus.DISCOVERING
                    updateNotification("Yeniden bağlanılıyor... ($reconnectAttempts)")
                    delay(2_000L * reconnectAttempts)
                    if (connectionStatus.value == ConnectionStatus.DISCOVERING) pairWithDevice(device)
                    return@launch
                }
                reconnectAttempts = 0
                pendingAuth = null
                errorMessage.value = "Eşleşme hatası: ${e.message}"
                connectionStatus.value = ConnectionStatus.ERROR
            }
        }
    }

    fun pairWithPin(code: String) {
        pairingCode.value = code
        val currentDevices = discoveredDevices.value
        // Be more lenient: any discovered device is a candidate for PIN pairing
        val target = currentDevices.firstOrNull() 
        
        if (target != null) {
            Log.d(TAG, "PIN entered: Connecting to ${target.name} at ${target.ipAddress}")
            pairWithDevice(target)
        } else {
            Log.d(TAG, "PIN entered: No device found yet, waiting for any device to appear...")
            // Create a temporary job to watch for discovery
            serviceScope.launch {
                errorMessage.value = "Cihaz aranıyor..."
                withTimeoutOrNull(20000) { // Increased to 20s
                    discoveredDevices.first { it.isNotEmpty() }
                }?.let { newDevices ->
                    newDevices.firstOrNull()?.let { 
                        Log.d(TAG, "Device discovered during PIN wait, connecting now")
                        pairWithDevice(it) 
                    }
                } ?: run {
                    Log.e(TAG, "PIN pairing failed: Device discovery timed out")
                    errorMessage.value = "Cihaz bulunamadı. Mac IP adresini 'Gelişmiş Seçenekler' kısmından manuel girmeyi deneyin."
                }
            }
        }
    }

    fun pairWithIp(ip: String, pin: String) {
        pairingCode.value = pin
        val device = Device(
            id = "manual_ip_device",
            name = "Mac (Manuel)",
            deviceType = "macOS",
            ipAddress = ip,
            port = 9876 // Default port
        )
        Log.d(TAG, "Connecting via manual IP: $ip")
        pairWithDevice(device)
    }

    fun rejectPairing() {
        serviceScope.launch {
            val response = Packet(
                packetId = UUID.randomUUID().toString(),
                type = PacketType.HANDSHAKE_RESPONSE,
                senderId = discoveryManager.getDeviceId(),
                timestamp = System.currentTimeMillis(),
                payload = mapOf(
                    "status" to "REJECTED",
                    "reason" to "Kullanıcı eşleşmeyi reddetti"
                )
            )
            transferManager.sendPacket(response, encrypt = false)
            connectionStatus.value = ConnectionStatus.DISCONNECTED
            updateNotification("Synapse hazır — bağlantı bekleniyor")
        }
    }

    // MARK: - Incoming Packets

    private fun listenForIncomingPackets() {
        serviceScope.launch {
            transferManager.receivePackets().collect { packet ->
                // Commands are only honoured inside an established, authenticated session.
                if (packet.type != PacketType.HANDSHAKE_REQUEST && packet.type != PacketType.HANDSHAKE_RESPONSE &&
                    connectionStatus.value != ConnectionStatus.CONNECTED
                ) {
                    Log.w(TAG, "⛔ Ignoring ${packet.type}: not connected")
                    return@collect
                }
                when (packet.type) {
                    PacketType.HANDSHAKE_REQUEST -> {
                        // The phone only initiates sessions; it never grants one to an inbound peer.
                        Log.w(TAG, "⛔ Ignoring inbound handshake from ${packet.senderId}")
                    }
                    PacketType.HANDSHAKE_RESPONSE -> {
                        if (connectionStatus.value != ConnectionStatus.PAIRING) {
                            Log.w(TAG, "⛔ Unexpected HANDSHAKE_RESPONSE, ignoring")
                            return@collect
                        }
                        val status = packet.payload["status"] as? String
                        val auth = pendingAuth
                        val sessionKey = transferManager.encryptionKey
                        if (status == "ACCEPTED" && (sessionKey == null || auth == null)) {
                            // "Accepted" without a usable key would mean a plaintext session: refuse it.
                            Log.e(TAG, "❌ Peer accepted without a valid key exchange, dropping")
                            pendingAuth = null
                            disconnect()
                            errorMessage.value = "Güvenli bağlantı kurulamadı"
                        } else if (status == "ACCEPTED" && sessionKey != null && auth != null) {
                            // Session key was already installed on the reader thread (handshakeKeyProvider).
                            pendingAuth = null
                            reconnectAttempts = 0
                            connectionStatus.value = ConnectionStatus.CONNECTED
                            val deviceName = connectedDevice.value?.name ?: "Cihaz"
                            updateNotification("Bağlı: $deviceName")
                            
                            // Persist this device as known; a PIN pairing yields the long-term token.
                            saveKnownDevice(packet.senderId)
                            if (auth.mode == CryptoManager.AUTH_MODE_PIN) {
                                savePairingToken(packet.senderId, CryptoManager.deriveDeviceToken(sessionKey))
                            }
                            connectedDevice.value?.let { savePeerIdForAddress(it.ipAddress, it.port, packet.senderId) }
                            
                            lastConnectedDeviceId = connectedDevice.value?.id
                            startClipboardMonitoring()
                            startHeartbeat()
                            sendWallpaper()
                            registerWallpaperChangeListener()
                        } else {
                            val reasonCode = packet.payload["reason_code"] as? String
                            if (auth?.mode == CryptoManager.AUTH_MODE_TOKEN) {
                                // Stored token no longer accepted (forgotten on the peer, or PIN required):
                                // drop it so the next attempt asks for the PIN.
                                removePairingToken(auth.targetId)
                                removePairingToken(packet.senderId)
                            }
                            pendingAuth = null
                            disconnect()
                            errorMessage.value = when (reasonCode) {
                                "BAD_PIN" -> "PIN kodu hatalı"
                                "PIN_REQUIRED", "UNKNOWN_DEVICE" -> "Eşleşme yenilenmeli: karşı cihazdaki PIN kodunu girin"
                                "OUTDATED_CLIENT" -> "Karşı cihazdaki uygulama sürümü uyumsuz"
                                else -> "Eşleşme reddedildi"
                            }
                            updateNotification("Eşleşme reddedildi")
                        }
                    }
                    PacketType.CLIPBOARD_TRANSFER -> {
                        val content = packet.payload["content"] as? String ?: ""
                        incomingClipboard.value = content
                        val clip = ClipData.newPlainText("Synapse", content)
                        clipboardManager.setPrimaryClip(clip)
                        lastClipboardText = content
                        Log.d(TAG, "Received clipboard (${content.length} chars)")
                    }
                    PacketType.HEARTBEAT -> {
                        Log.d(TAG, "Heartbeat from ${packet.senderId}")
                    }
                    PacketType.CALL_COMMAND -> {
                        val number = packet.payload["number"] as? String
                        number?.let { makeCall(it) }
                    }
                    PacketType.SMS_COMMAND -> {
                        val number = packet.payload["number"] as? String
                        val body = packet.payload["body"] as? String
                        if (number != null && body != null) {
                            sendSms(number, body)
                        }
                    }
                    PacketType.SCREEN_STREAM_COMMAND -> {
                        val command = packet.payload["command"] as? String
                        if (command == "START") {
                            startScreenMirroring()
                        } else if (command == "STOP") {
                            stopScreenMirroring()
                        }
                    }
                    PacketType.REMOTE_INPUT -> {
                        val action = packet.payload["action"] as? String ?: "CLICK"
                        
                        if (action == "KEYBOARD") {
                            val key = packet.payload["key"] as? String ?: ""
                            val type = packet.payload["key_type"] as? String ?: "STRING"
                            SynapseAccessibilityService.instance?.executeKeyboardAction(key, type)
                                ?: Log.w(TAG, "Accessibility Service is not running, cannot execute keyboard action.")
                        } else {
                            val x = (packet.payload["x"] as? Number)?.toDouble() ?: 0.0
                            val y = (packet.payload["y"] as? Number)?.toDouble() ?: 0.0
                            SynapseAccessibilityService.instance?.executeRemoteAction(x, y, action)
                                ?: Log.w(TAG, "Accessibility Service is not running, cannot execute remote action.")
                        }
                    }
                    PacketType.FILE_TRANSFER -> {
                        val fileName = sanitizeFileName(packet.payload["file_name"] as? String)
                        val fileDataB64 = packet.payload["file_data"] as? String
                        if (fileDataB64 != null) {
                            try {
                                val bytes = android.util.Base64.decode(fileDataB64, android.util.Base64.DEFAULT)
                                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                                val synapseDir = java.io.File(downloadsDir, "Synapse")
                                if (!synapseDir.exists()) synapseDir.mkdirs()
                                
                                val file = uniqueFile(synapseDir, fileName)
                                // Defense in depth: the resolved path must stay inside Downloads/Synapse.
                                if (file.canonicalFile.parentFile != synapseDir.canonicalFile) {
                                    Log.e(TAG, "⛔ Refusing to write outside the Synapse folder")
                                    return@collect
                                }
                                java.io.FileOutputStream(file).use { it.write(bytes) }
                                
                                updateNotification("Dosya alındı: ${file.name}")
                                Log.d(TAG, "File saved successfully: ${file.absolutePath}")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error saving file: ${e.message}")
                            }
                        }
                    }
                    PacketType.PHOTO_LIBRARY_REQ -> {
                        fetchPhotoLibraryThumbnails()
                    }
                    PacketType.PHOTO_DOWNLOAD_REQ -> {
                        val photoId = packet.payload["photo_id"] as? String
                        if (photoId != null) {
                            handlePhotoDownloadRequest(photoId)
                        }
                    }
                    PacketType.PHOTO_DELETE_REQ -> {
                        val photoId = packet.payload["photo_id"] as? String
                        if (photoId != null) {
                            handleDeletePhotoRequest(photoId)
                        }
                    }
                    PacketType.WALLPAPER_REQUEST -> {
                        Log.d(TAG, "🖼️ Wallpaper requested by Mac, sending...")
                        sendWallpaper()
                    }
                    else -> {
                        Log.d(TAG, "Unhandled packet type: ${packet.type}")
                    }
                }
            }
        }
    }

    /** Keeps only a bare file name: strips directories, "..", control and reserved characters. */
    private fun sanitizeFileName(raw: String?): String {
        val base = raw?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.replace(Regex("[\\x00-\\x1f:*?\"<>|]"), "_")
            ?.trim()?.trimStart('.')
            ?.take(200)
        return if (base.isNullOrBlank()) "file_${System.currentTimeMillis()}" else base
    }

    /** Never overwrite an existing file: append " (n)" before the extension. */
    private fun uniqueFile(dir: java.io.File, name: String): java.io.File {
        var file = java.io.File(dir, name)
        val stem = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var n = 1
        while (file.exists()) file = java.io.File(dir, "$stem (${n++})$ext")
        return file
    }

    private fun makeCall(number: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = android.net.Uri.parse("tel:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to make call: ${e.message}")
        }
    }

    private fun sendSms(number: String, body: String) {
        try {
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                this.getSystemService(android.telephony.SmsManager::class.java)
            } else {
                android.telephony.SmsManager.getDefault()
            }
            smsManager.sendTextMessage(number, null, body, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS: ${e.message}")
        }
    }

    // MARK: - Clipboard

    private fun startClipboardMonitoring() {
        acquireWakeLock()
        // No periodic polling needed! clipboardManager.addPrimaryClipChangedListener handles real-time clipboard sync perfectly.
    }

    private fun checkClipboardChange() {
        try {
            if (!clipboardManager.hasPrimaryClip()) return
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: return
                if (text != lastClipboardText && text.isNotBlank()) {
                    lastClipboardText = text
                    clipboardContent.value = text
                    sendClipboard(text)
                }
            }
        } catch (e: SecurityException) {
            // App is not in focus, system denied clipboard access. Ignore quietly.
        } catch (e: Exception) {
            // Ignore other exceptions
        }
    }

    private fun sendClipboard(text: String) {
        if (connectionStatus.value != ConnectionStatus.CONNECTED) return

        serviceScope.launch {
            try {
                val packet = Packet(
                    packetId = UUID.randomUUID().toString(),
                    type = PacketType.CLIPBOARD_TRANSFER,
                    senderId = discoveryManager.getDeviceId(),
                    timestamp = System.currentTimeMillis(),
                    payload = mapOf(
                        "content_type" to "TEXT",
                        "content" to text,
                        "metadata" to mapOf(
                            "origin_app" to "Android",
                            "size" to text.length
                        )
                    )
                )
                transferManager.sendPacket(packet)
                Log.d(TAG, "Sent clipboard (${text.length} chars)")
            } catch (e: Exception) {
                Log.e(TAG, "Send clipboard error", e)
            }
        }
    }

    // MARK: - Notification Forwarding

    fun sendNotification(appName: String, title: String, text: String, iconBase64: String, packageName: String) {
        if (connectionStatus.value != ConnectionStatus.CONNECTED) return

        serviceScope.launch {
            try {
                val packet = Packet(
                    packetId = UUID.randomUUID().toString(),
                    type = PacketType.NOTIFICATION_EVENT,
                    senderId = discoveryManager.getDeviceId(),
                    timestamp = System.currentTimeMillis(),
                    payload = mapOf(
                        "app_name" to appName,
                        "title" to title,
                        "text" to text,
                        "icon" to iconBase64,
                        "package_name" to packageName
                    )
                )
                transferManager.sendPacket(packet)
                Log.d(TAG, "Sent notification from $packageName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send notification", e)
            }
        }
    }

    // MARK: - Heartbeat

    fun sendWallpaper() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "🖼️ sendWallpaper() called, attempting to fetch wallpaper...")
                val wallpaperManager = WallpaperManager.getInstance(this@SynapseService)
                var bitmap: Bitmap? = null
                val errors = mutableListOf<String>()

                // Yöntem 1: getWallpaperFile() — API 24+
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    try {
                        val pfd = wallpaperManager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)
                        if (pfd != null) {
                            val stream = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
                            bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                            stream.close()
                            if (bitmap != null) Log.d(TAG, "✅ Method 1 (getWallpaperFile) succeeded: ${bitmap.width}x${bitmap.height}")
                            else errors.add("Method1: decodeStream returned null")
                        } else {
                            errors.add("Method1: getWallpaperFile returned null pfd")
                        }
                    } catch (se: SecurityException) {
                        errors.add("Method1: SecurityException - ${se.message}")
                    } catch (e: Exception) {
                        errors.add("Method1: ${e.javaClass.simpleName} - ${e.message}")
                    }
                }

                // Yöntem 2: drawable
                if (bitmap == null) {
                    try {
                        val drawable = wallpaperManager.drawable ?: wallpaperManager.peekDrawable()
                        if (drawable != null) {
                            bitmap = if (drawable is BitmapDrawable) {
                                drawable.bitmap?.also { Log.d(TAG, "✅ Method 2a (BitmapDrawable) succeeded") }
                            } else {
                                val w = drawable.intrinsicWidth.coerceAtLeast(360)
                                val h = drawable.intrinsicHeight.coerceAtLeast(720)
                                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(b)
                                drawable.setBounds(0, 0, w, h)
                                drawable.draw(canvas)
                                Log.d(TAG, "✅ Method 2b (drawable render) succeeded")
                                b
                            }
                        } else {
                            errors.add("Method2: drawable is null")
                        }
                    } catch (se: SecurityException) {
                        errors.add("Method2: SecurityException - ${se.message}")
                    } catch (e: Exception) {
                        errors.add("Method2: ${e.javaClass.simpleName} - ${e.message}")
                    }
                }

                // Yöntem 3: WallpaperColors → Gradient
                if (bitmap == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                    try {
                        val colors = wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                        if (colors != null) {
                            val primary = colors.primaryColor.toArgb()
                            val secondary = colors.secondaryColor?.toArgb() ?: primary
                            val b = Bitmap.createBitmap(360, 720, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(b)
                            val paint = android.graphics.Paint()
                            val shader = android.graphics.LinearGradient(0f, 0f, 0f, 720f, primary, secondary, android.graphics.Shader.TileMode.CLAMP)
                            paint.shader = shader
                            canvas.drawRect(0f, 0f, 360f, 720f, paint)
                            bitmap = b
                            Log.d(TAG, "✅ Method 3 (WallpaperColors gradient) succeeded: #${Integer.toHexString(primary)} → #${Integer.toHexString(secondary)}")
                        } else {
                            errors.add("Method3: getWallpaperColors returned null")
                        }
                    } catch (se: SecurityException) {
                        errors.add("Method3: SecurityException - ${se.message}")
                    } catch (e: Exception) {
                        errors.add("Method3: ${e.javaClass.simpleName} - ${e.message}")
                    }
                }

                // Yöntem 4: Son Çare (Default Gradient)
                if (bitmap == null) {
                    Log.w(TAG, "⚠️ All wallpaper methods failed. Falling back to default gradient. Errors: ${errors.joinToString(" | ")}")
                    val b = Bitmap.createBitmap(360, 720, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(b)
                    val paint = android.graphics.Paint()
                    // Zarif bir koyu mavi/mor degrade
                    val shader = android.graphics.LinearGradient(
                        0f, 0f, 360f, 720f,
                        android.graphics.Color.parseColor("#1A1A2E"),
                        android.graphics.Color.parseColor("#16213E"),
                        android.graphics.Shader.TileMode.CLAMP
                    )
                    paint.shader = shader
                    canvas.drawRect(0f, 0f, 360f, 720f, paint)
                    bitmap = b
                }

                // Merkezi kırpma ve ölçeklendirme
                val targetW = 360; val targetH = 720
                val srcW = bitmap.width; val srcH = bitmap.height
                val srcRatio = srcW.toFloat() / srcH
                val tgtRatio = targetW.toFloat() / targetH
                val (cropW, cropH, startX, startY) = if (srcRatio > tgtRatio) {
                    val cw = (srcH * tgtRatio).toInt()
                    listOf(cw, srcH, (srcW - cw) / 2, 0)
                } else {
                    val ch = (srcW / tgtRatio).toInt()
                    listOf(srcW, ch, 0, (srcH - ch) / 2)
                }

                val cropped = Bitmap.createBitmap(bitmap, startX, startY, cropW, cropH)
                val scaled = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
                val outputStream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

                val packet = Packet(
                    packetId = UUID.randomUUID().toString(),
                    type = PacketType.WALLPAPER_TRANSFER,
                    senderId = discoveryManager.getDeviceId(),
                    timestamp = System.currentTimeMillis(),
                    payload = mapOf("image" to base64Image)
                )
                transferManager.sendPacket(packet)
                Log.d(TAG, "✅ Wallpaper sent (${outputStream.size()} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ sendWallpaper outer exception: ${e.message}", e)
            }
        }
    }



    private var wallpaperChangeReceiver: android.content.BroadcastReceiver? = null

    private fun registerWallpaperChangeListener() {
        if (wallpaperChangeReceiver != null) return // Zaten kayıtlı
        wallpaperChangeReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action == android.content.Intent.ACTION_WALLPAPER_CHANGED) {
                    Log.d(TAG, "🖼️ Wallpaper changed, sending update to Mac...")
                    sendWallpaper()
                }
            }
        }
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_WALLPAPER_CHANGED)
        registerReceiver(wallpaperChangeReceiver, filter)
    }

    private fun unregisterWallpaperChangeListener() {
        wallpaperChangeReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        wallpaperChangeReceiver = null
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = serviceScope.launch {
            var firstHeartbeat = true
            while (connectionStatus.value == ConnectionStatus.CONNECTED) {
                delay(if (firstHeartbeat) 1000L else HEARTBEAT_INTERVAL)
                if (connectionStatus.value != ConnectionStatus.CONNECTED) break
                try {
                    val batteryManager = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                    val batteryLevel = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    
                    val heartbeat = Packet(
                        packetId = UUID.randomUUID().toString(),
                        type = PacketType.HEARTBEAT,
                        senderId = discoveryManager.getDeviceId(),
                        timestamp = System.currentTimeMillis(),
                        payload = mapOf("battery_level" to batteryLevel)
                    )
                    transferManager.sendPacket(heartbeat)
                    Log.d(TAG, "💓 Heartbeat sent (Battery: $batteryLevel%)")
                    
                    if (firstHeartbeat) {
                        sendWallpaper()
                        firstHeartbeat = false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat send error", e)
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // MARK: - Screen Mirroring

    private fun startScreenMirroring() {
        if (isStreaming.value) return
        if (mediaProjection != null && virtualDisplay != null) {
            Log.d(TAG, "▶️ Resuming existing VirtualDisplay stream")
            isStreaming.value = true
            return
        }
        if (mediaProjection != null) {
            Log.d(TAG, "♻️ Reusing existing MediaProjection session")
            isStreaming.value = true
            setupScreenCapture()
            return
        }
        // Signal the activity to request permission
        val intent = Intent("com.serkantkn.synapse.ACTION_REQUEST_SCREEN_CAPTURE").apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.d(TAG, "📡 Broadcast sent for Screen Capture permission")
    }


    private var imageHandlerThread: android.os.HandlerThread? = null

    private fun setupScreenCapture() {
        val metrics = resources.displayMetrics
        val width = 640 // Optimized resolution for higher FPS
        val height = (width * metrics.heightPixels / metrics.widthPixels)
        val density = metrics.densityDpi

        // Create a dedicated thread for image processing to offload the CPU
        imageHandlerThread?.quitSafely()
        imageHandlerThread = android.os.HandlerThread("MirroringThread").apply { start() }
        val handler = android.os.Handler(imageHandlerThread!!.looper)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                if (isStreaming.value) {
                    processScreenFrame(image)
                }
                image.close()
            }
        }, handler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SynapseMirror",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )
        
        Log.d(TAG, "🚀 GPU-accelerated screen capture started at ${width}x${height}")
    }

    private fun processScreenFrame(image: Image) {
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val fullBitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)

            // Crop to actual width to remove the padding-induced black/garbage bar on the right
            val cleanBitmap = Bitmap.createBitmap(fullBitmap, 0, 0, image.width, image.height)

            val outputStream = ByteArrayOutputStream()
            cleanBitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream)
            
            // Recycle bitmaps to free memory
            fullBitmap.recycle()
            cleanBitmap.recycle()
            val base64Frame = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

            serviceScope.launch {
                val isAccActive = SynapseAccessibilityService.instance != null
                val packet = Packet(
                    packetId = UUID.randomUUID().toString(),
                    type = PacketType.SCREEN_FRAME,
                    senderId = discoveryManager.getDeviceId(),
                    timestamp = System.currentTimeMillis(),
                    payload = mapOf(
                        "image" to base64Frame,
                        "accessibility_enabled" to isAccActive
                    )
                )
                transferManager.sendPacket(packet)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing screen frame", e)
        }
    }

    fun stopScreenMirroring() {
        isStreaming.value = false
        Log.d(TAG, "⏸️ Screen mirroring paused (session kept fully active)")
        // Do NOT release virtualDisplay, imageReader, or mediaProjection!
        // This ensures 0% CPU usage while paused, and avoids Android 14 MediaProjection lifecycle exceptions when restarting!
        // The foreground service type is left unchanged: the projection is still alive.
    }

    /**
     * Releases all capture resources. Safe to call repeatedly. Nulls [mediaProjection]
     * before calling stop() so the resulting onStop callback doesn't recurse.
     */
    private fun releaseScreenCapture() {
        isStreaming.value = false
        imageReader?.setOnImageAvailableListener(null, null)
        imageHandlerThread?.quitSafely()
        imageHandlerThread = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        val projection = mediaProjection
        mediaProjection = null
        projection?.stop()
    }

    /** The projection was stopped (by us or the system): release it and drop the FGS type. */
    private fun onProjectionStopped() {
        Log.d(TAG, "⏹️ MediaProjection stopped")
        releaseScreenCapture()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(if (connectionStatus.value == ConnectionStatus.CONNECTED) "Bağlı" else "Synapse hazır"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        }
    }

    // MARK: - Photo Library
    
    private fun fetchPhotoLibraryThumbnails() {
        serviceScope.launch(Dispatchers.IO) {
            val photosList = mutableListOf<Map<String, Any>>()
            try {
                val projection = arrayOf(
                    android.provider.MediaStore.Images.Media._ID,
                    android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                    android.provider.MediaStore.Images.Media.DATE_ADDED,
                    android.provider.MediaStore.Images.Media.SIZE
                )
                val sortOrder = "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"
                
                contentResolver.query(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                    val dateCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DATE_ADDED)
                    val sizeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.SIZE)
                    
                    var count = 0
                    while (cursor.moveToNext() && count < 50) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "unknown"
                        val date = cursor.getLong(dateCol)
                        val size = cursor.getLong(sizeCol)
                        
                        val uri = android.content.ContentUris.withAppendedId(
                            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                        )
                        
                        var base64Thumb = ""
                        try {
                            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                contentResolver.loadThumbnail(uri, android.util.Size(256, 256), null)
                            } else {
                                android.provider.MediaStore.Images.Thumbnails.getThumbnail(
                                    contentResolver, id, android.provider.MediaStore.Images.Thumbnails.MINI_KIND, null
                                )
                            }
                            
                            if (bitmap != null) {
                                val stream = java.io.ByteArrayOutputStream()
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, stream)
                                val bytes = stream.toByteArray()
                                base64Thumb = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                bitmap.recycle()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load thumbnail for $id", e)
                        }
                        
                        photosList.add(mapOf(
                            "id" to id.toString(),
                            "name" to name,
                            "date" to date,
                            "size" to size,
                            "thumbnail" to base64Thumb
                        ))
                        count++
                    }
                }
                
                val packet = Packet(
                    packetId = UUID.randomUUID().toString(),
                    type = PacketType.PHOTO_LIBRARY_RESP,
                    senderId = discoveryManager.getDeviceId(),
                    timestamp = System.currentTimeMillis(),
                    payload = mapOf("photos" to photosList)
                )
                transferManager.sendPacket(packet)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching photo library", e)
            }
        }
    }

    private fun handlePhotoDownloadRequest(photoId: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val id = photoId.toLongOrNull() ?: return@launch
                val uri = android.content.ContentUris.withAppendedId(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )
                
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        val outStream = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, outStream)
                        val compressedBytes = outStream.toByteArray()
                        val base64Data = android.util.Base64.encodeToString(compressedBytes, android.util.Base64.NO_WRAP)
                        bitmap.recycle()
                        
                        val packet = Packet(
                            packetId = UUID.randomUUID().toString(),
                            type = PacketType.PHOTO_DOWNLOAD_RESP,
                            senderId = discoveryManager.getDeviceId(),
                            timestamp = System.currentTimeMillis(),
                            payload = mapOf(
                                "photo_id" to photoId,
                                "data" to base64Data
                            )
                        )
                        transferManager.sendPacket(packet)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading photo $photoId", e)
            }
        }
    }

    private fun handleDeletePhotoRequest(photoId: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val id = photoId.toLongOrNull() ?: return@launch
                val uri = android.content.ContentUris.withAppendedId(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )
                try {
                    contentResolver.delete(uri, null, null)
                    Log.d(TAG, "Deleted photo $photoId successfully from MediaStore.")
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException deleting photo: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting photo $photoId", e)
            }
        }
    }



    // MARK: - Disconnect

    fun disconnect() {
        if (mediaProjection != null) onProjectionStopped() else releaseScreenCapture()

        clipboardHandler.removeCallbacks(clipboardRunnable)
        stopHeartbeat()
        unregisterWallpaperChangeListener()
        releaseWakeLock()
        transferManager.disconnect()
        connectionStatus.value = ConnectionStatus.DISCONNECTED
        connectedDevice.value = null
        incomingClipboard.value = null
        updateNotification("Synapse hazır — bağlantı bekleniyor")
    }

    fun getSynapseDeviceId(): String = discoveryManager.getDeviceId()

    fun setPairingCode(code: String) {
        pairingCode.value = code
    }

    fun getQrCodeData(): String {
        val deviceId = discoveryManager.getDeviceId()
        val publicKey = keyPair?.let { CryptoManager.encodePublicKey(it.public) } ?: ""
        return "synapse://$deviceId/${pairingCode.value}/$publicKey"
    }

    fun clearError() {
        errorMessage.value = null
    }
}
