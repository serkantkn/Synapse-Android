package com.serkantkn.synapse.data.network.socket

import android.util.Log
import com.google.gson.Gson
import com.serkantkn.synapse.data.dto.PacketDto
import com.serkantkn.synapse.data.network.crypto.CryptoManager
import com.serkantkn.synapse.data.network.mapper.PacketMapper
import com.serkantkn.synapse.domain.model.Packet
import com.serkantkn.synapse.domain.repository.TransferRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import javax.crypto.SecretKey

/**
 * TCP socket-based data transfer implementation.
 *
 * Uses 4-byte length-prefixed framing:
 * [4 bytes: payload length][N bytes: JSON payload (optionally encrypted)]
 *
 * This implementation is designed to handle ONE active connection at a time
 * to prevent stream corruption during handshake race conditions.
 */
class TcpTransferManager : TransferRepository {

    companion object {
        private const val TAG = "TcpTransfer"
    }

    private val gson = Gson()
    private val incomingPackets = MutableSharedFlow<Packet>(extraBufferCapacity = 64)

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outputStream: DataOutputStream? = null
    private var inputStream: DataInputStream? = null

    /** Shared encryption key — null means plaintext (pre-handshake). */
    var encryptionKey: SecretKey? = null

    /** Callback for spontaneous disconnection. */
    var onDisconnect: (() -> Unit)? = null

    private var isRunning = false

    override suspend fun startServer(): Int = withContext(Dispatchers.IO) {
        if (serverSocket != null) return@withContext serverSocket!!.localPort
        
        serverSocket = ServerSocket(0)
        val port = serverSocket!!.localPort
        Log.d(TAG, "✅ Server listening on port $port")

        isRunning = true
        Thread {
            try {
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    Log.d(TAG, "📥 Incoming connection request from ${socket.inetAddress.hostAddress}")
                    
                    synchronized(this@TcpTransferManager) {
                        if (clientSocket != null && !clientSocket!!.isClosed) {
                            Log.w(TAG, "⚠️ Connection already active. Rejecting new one.")
                            try { socket.close() } catch (ignored: Exception) {}
                        } else {
                            handleConnection(socket)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "Server error", e)
            }
        }.start()

        port
    }

    override suspend fun connectTo(ipAddress: String, port: Int): Unit {
        withContext(Dispatchers.IO) {
            synchronized(this@TcpTransferManager) {
                if (clientSocket != null && !clientSocket!!.isClosed) {
                    Log.d(TAG, "Closing existing connection before connecting to $ipAddress:$port")
                    disconnect()
                }
            }
            
            try {
                Log.d(TAG, "🚀 Connecting to $ipAddress:$port...")
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(ipAddress, port), 5000) // 5s timeout
                handleConnection(socket)
                Log.d(TAG, "✅ Connected to $ipAddress")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Connection failed to $ipAddress:$port: ${e.message}")
                throw e
            }
        }
    }

    @Synchronized
    private fun handleConnection(socket: Socket) {
        Log.d(TAG, "Setting up connection with ${socket.inetAddress.hostAddress}")
        clientSocket = socket
        outputStream = DataOutputStream(socket.getOutputStream())
        inputStream = DataInputStream(socket.getInputStream())

        Thread {
            try {
                while (socket.isConnected && !socket.isClosed) {
                    val packet = readPacket() ?: break
                    incomingPackets.tryEmit(packet)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Read thread interrupted: ${e.message}")
            } finally {
                Log.d(TAG, "Read thread terminated for ${socket.inetAddress.hostAddress}")
                if (clientSocket == socket) {
                    onDisconnect?.invoke()
                }
            }
        }.start()
    }

    override suspend fun sendPacket(packet: Packet): Unit = sendPacket(packet, true)

    suspend fun sendPacket(packet: Packet, encrypt: Boolean) {
        val out = synchronized(this) { outputStream }
        if (out == null) {
            Log.e(TAG, "Cannot send packet: No active output stream")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val dto = PacketMapper.toDto(packet)
                val json = gson.toJson(dto)
                val jsonBytes = json.toByteArray(Charsets.UTF_8)

                val dataToSend = if (encrypt) {
                    encryptionKey?.let { key ->
                        CryptoManager.encrypt(jsonBytes, key)
                    } ?: jsonBytes
                } else {
                    jsonBytes
                }

                synchronized(this@TcpTransferManager) {
                    outputStream?.let { stream ->
                        stream.writeInt(dataToSend.size)
                        stream.write(dataToSend)
                        stream.flush()
                        val prefix = if (encrypt && encryptionKey != null) "🔒" else "🔓"
                        Log.d(TAG, "$prefix Sent: ${packet.type} (${dataToSend.size} bytes)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error sending packet", e)
            }
        }
    }

    private fun readPacket(): Packet? {
        val stream = synchronized(this) { inputStream } ?: return null
        return try {
            // 4-byte length prefix
            val length = stream.readInt()
            if (length <= 0 || length > 10_000_000) {
                Log.e(TAG, "❌ Invalid packet length received: $length")
                return null
            }

            val data = ByteArray(length)
            stream.readFully(data)

            val jsonBytes = encryptionKey?.let { key ->
                CryptoManager.decrypt(data, key)
            } ?: data

            val json = String(jsonBytes, Charsets.UTF_8)
            // Log.v(TAG, "⬇️ Received JSON: $json")
            
            val dto = gson.fromJson(json, PacketDto::class.java)
            PacketMapper.toDomain(dto)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error reading packet: ${e.message}")
            null
        }
    }

    override fun receivePackets(): Flow<Packet> = incomingPackets

    @Synchronized
    override fun disconnect() {
        Log.d(TAG, "Cleaning up connections...")
        isRunning = false
        try {
            inputStream?.close()
            outputStream?.close()
            clientSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
        inputStream = null
        outputStream = null
        clientSocket = null
        encryptionKey = null
    }
}

