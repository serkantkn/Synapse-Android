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
                    stopScreenMirroring()
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
            checkClipboardChange()
            clipboardHandler.postDelayed(this, CLIPBOARD_POLL_INTERVAL)
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
            Log.d(TAG, "🔌 Connection lost detected by TCP manager")
            // Save the device ID before cleaning up, so we can auto-reconnect
            lastConnectedDeviceId = connectedDevice.value?.id
            disconnect()
            // Auto-restart discovery (will auto-reconnect if device is found)
            startDiscovery()
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
        transferManager.disconnect()
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

    fun startDiscovery() {
        serviceScope.launch {
            try {
                connectionStatus.value = ConnectionStatus.DISCOVERING
                updateNotification("Cihaz aranıyor...")

                serverPort = transferManager.startServer()
                Log.d(TAG, "TCP server on port: $serverPort")

                discoveryManager.registerService(serverPort)

                discoveryJob = launch {
                    discoveryManager.discoverDevices().collect { devices ->
                        discoveredDevices.value = devices
                        
                        // Auto-reconnect: if a previously paired device is found, reconnect automatically
                        val knownDevices = getKnownDevices()
                        if (connectionStatus.value == ConnectionStatus.DISCOVERING) {
                            val matchingDevice = devices.find { knownDevices.contains(it.id) || it.id == lastConnectedDeviceId }
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
                // Only generate a new code if we don't have one set (e.g. from manual entry or QR)
                if (pairingCode.value.isEmpty()) {
                    pairingCode.value = generatePairingCode()
                }
                updateNotification("Eşleşme: ${device.name}...")

                transferManager.connectTo(device.ipAddress, device.port)

                val publicKeyStr = keyPair?.let { CryptoManager.encodePublicKey(it.public) } ?: ""
                val handshakePacket = Packet(
                    packetId = UUID.randomUUID().toString(),
                    type = PacketType.HANDSHAKE_REQUEST,
                    senderId = discoveryManager.getDeviceId(),
                    timestamp = System.currentTimeMillis(),
                    payload = mapOf(
                        "device_name" to android.os.Build.MODEL,
                        "device_type" to "android",
                        "public_key" to publicKeyStr,
                        "pairing_code" to pairingCode.value
                    )
                )
                transferManager.sendPacket(handshakePacket, encrypt = false)
                connectedDevice.value = device

            } catch (e: Exception) {
                Log.e(TAG, "Pairing error", e)
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

    fun acceptPairing(incomingPacket: Packet) {
        serviceScope.launch {
            try {
                val theirPublicKeyStr = incomingPacket.payload["public_key"] as? String
                if (theirPublicKeyStr != null && keyPair != null) {
                    val theirPublicKey = CryptoManager.decodePublicKey(theirPublicKeyStr)
                    val sharedKey = CryptoManager.deriveSharedSecret(keyPair!!.private, theirPublicKey)
                    transferManager.encryptionKey = sharedKey
                }

                val publicKeyStr = keyPair?.let { CryptoManager.encodePublicKey(it.public) } ?: ""
                val response = Packet(
                    packetId = UUID.randomUUID().toString(),
                    type = PacketType.HANDSHAKE_RESPONSE,
                    senderId = discoveryManager.getDeviceId(),
                    timestamp = System.currentTimeMillis(),
                    payload = mapOf(
                        "status" to "ACCEPTED",
                        "public_key" to publicKeyStr
                    )
                )
                transferManager.sendPacket(response, encrypt = false)
                connectionStatus.value = ConnectionStatus.CONNECTED
                updateNotification("Bağlı: ${incomingPacket.payload["device_name"] ?: "Cihaz"}")
                lastConnectedDeviceId = incomingPacket.senderId
                startClipboardMonitoring()
                startHeartbeat()
                sendWallpaper()

            } catch (e: Exception) {
                Log.e(TAG, "Accept pairing error", e)
                errorMessage.value = "Eşleşme kabul hatası: ${e.message}"
            }
        }
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
                when (packet.type) {
                    PacketType.HANDSHAKE_REQUEST -> {
                        val deviceId = packet.senderId
                        if (isKnownDevice(deviceId)) {
                            Log.d(TAG, "🤝 Auto-accepting known device: $deviceId")
                            acceptPairing(packet)
                        } else {
                            connectionStatus.value = ConnectionStatus.PAIRING
                            incomingClipboard.value = null
                        }
                    }
                    PacketType.HANDSHAKE_RESPONSE -> {
                        val status = packet.payload["status"] as? String
                        if (status == "ACCEPTED") {
                            val theirPublicKeyStr = packet.payload["public_key"] as? String
                            if (theirPublicKeyStr != null && keyPair != null) {
                                val theirPublicKey = CryptoManager.decodePublicKey(theirPublicKeyStr)
                                val sharedKey = CryptoManager.deriveSharedSecret(keyPair!!.private, theirPublicKey)
                                transferManager.encryptionKey = sharedKey
                            }
                            connectionStatus.value = ConnectionStatus.CONNECTED
                            val deviceName = connectedDevice.value?.name ?: "Cihaz"
                            updateNotification("Bağlı: $deviceName")
                            
                            // Persist this device as known
                            packet.senderId.let { saveKnownDevice(it) }
                            
                            lastConnectedDeviceId = connectedDevice.value?.id
                            startClipboardMonitoring()
                            startHeartbeat()
                            sendWallpaper()
                        } else {
                            connectionStatus.value = ConnectionStatus.DISCONNECTED
                            errorMessage.value = "Eşleşme reddedildi"
                            updateNotification("Eşleşme reddedildi")
                        }
                    }
                    PacketType.CLIPBOARD_TRANSFER -> {
                        val content = packet.payload["content"] as? String ?: ""
                        incomingClipboard.value = content
                        val clip = ClipData.newPlainText("Synapse", content)
                        clipboardManager.setPrimaryClip(clip)
                        lastClipboardText = content
                        Log.d(TAG, "Received clipboard: ${content.take(50)}...")
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
                        val x = (packet.payload["x"] as? Number)?.toDouble() ?: 0.0
                        val y = (packet.payload["y"] as? Number)?.toDouble() ?: 0.0
                        val action = packet.payload["action"] as? String ?: "CLICK"
                        
                        SynapseAccessibilityService.instance?.executeRemoteAction(x, y, action)
                            ?: Log.w(TAG, "Accessibility Service is not running, cannot execute remote action.")
                    }
                    else -> {
                        Log.d(TAG, "Unhandled packet type: ${packet.type}")
                    }
                }
            }
        }
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
        clipboardHandler.post(clipboardRunnable)
    }

    private fun checkClipboardChange() {
        try {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: return
                if (text != lastClipboardText && text.isNotBlank()) {
                    lastClipboardText = text
                    clipboardContent.value = text
                    sendClipboard(text)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard check error", e)
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
                Log.d(TAG, "Sent clipboard: ${text.take(50)}...")
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
                Log.d(TAG, "Sent notification: [$appName] $title")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send notification", e)
            }
        }
    }

    // MARK: - Heartbeat

    private fun sendWallpaper() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "🖼️ Attempting to fetch device wallpaper...")
                val wallpaperManager = WallpaperManager.getInstance(this@SynapseService)
                
                // Try multiple ways to get the drawable
                val wallpaperDrawable = try {
                    wallpaperManager.drawable ?: wallpaperManager.peekDrawable()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error fetching wallpaper drawable: ${e.message}")
                    null
                }

                if (wallpaperDrawable == null) {
                    Log.w(TAG, "⚠️ Wallpaper drawable is null. Falling back to system default.")
                    return@launch
                }
                
                val originalBitmap = if (wallpaperDrawable is BitmapDrawable) {
                    wallpaperDrawable.bitmap
                } else {
                    val b = Bitmap.createBitmap(
                        wallpaperDrawable.intrinsicWidth.coerceAtLeast(1),
                        wallpaperDrawable.intrinsicHeight.coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    val canvas = Canvas(b)
                    wallpaperDrawable.setBounds(0, 0, canvas.width, canvas.height)
                    wallpaperDrawable.draw(canvas)
                    b
                }

                if (originalBitmap == null) return@launch

                // Mockup aspect ratio is approx 1:2 (180x360)
                // Let's crop the center of the wallpaper to match a phone screen aspect ratio
                val targetWidth = 360
                val targetHeight = 720
                
                val sourceWidth = originalBitmap.width
                val sourceHeight = originalBitmap.height
                
                val sourceAspectRatio = sourceWidth.toFloat() / sourceHeight.toFloat()
                val targetAspectRatio = targetWidth.toFloat() / targetHeight.toFloat()
                
                var cropWidth = sourceWidth
                var cropHeight = sourceHeight
                var startX = 0
                var startY = 0
                
                if (sourceAspectRatio > targetAspectRatio) {
                    cropWidth = (sourceHeight * targetAspectRatio).toInt()
                    startX = (sourceWidth - cropWidth) / 2
                } else {
                    cropHeight = (sourceWidth / targetAspectRatio).toInt()
                    startY = (sourceHeight - cropHeight) / 2
                }

                val croppedBitmap = Bitmap.createBitmap(originalBitmap, startX, startY, cropWidth, cropHeight)
                val scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, targetWidth, targetHeight, true)

                val outputStream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
                val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

                val packet = Packet(
                    packetId = UUID.randomUUID().toString(),
                    type = PacketType.WALLPAPER_TRANSFER,
                    senderId = discoveryManager.getDeviceId(),
                    timestamp = System.currentTimeMillis(),
                    payload = mapOf("image" to base64Image)
                )
                transferManager.sendPacket(packet)
                Log.d(TAG, "✅ Wallpaper sent successfully (${outputStream.size()} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to send wallpaper: ${e.message}")
            }
        }
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
        imageHandlerThread = android.os.HandlerThread("MirroringThread").apply { start() }
        val handler = android.os.Handler(imageHandlerThread!!.looper)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                processScreenFrame(image)
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
                val packet = Packet(
                    packetId = UUID.randomUUID().toString(),
                    type = PacketType.SCREEN_FRAME,
                    senderId = discoveryManager.getDeviceId(),
                    timestamp = System.currentTimeMillis(),
                    payload = mapOf("image" to base64Frame)
                )
                transferManager.sendPacket(packet)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing screen frame", e)
        }
    }

    fun stopScreenMirroring() {
        isStreaming.value = false
        imageReader?.setOnImageAvailableListener(null, null)
        
        imageHandlerThread?.quitSafely()
        imageHandlerThread = null
        
        virtualDisplay?.release()
        virtualDisplay = null
        
        imageReader?.close()
        imageReader = null
        
        mediaProjection?.stop()
        mediaProjection = null

        // Revert foreground service type to connectedDevice only
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(if (connectionStatus.value == ConnectionStatus.CONNECTED) "Bağlı" else "Synapse hazır"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        }
        
        Log.d(TAG, "🛑 Screen Mirroring stopped")
    }

    // MARK: - Disconnect

    fun disconnect() {
        clipboardHandler.removeCallbacks(clipboardRunnable)
        stopHeartbeat()
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
