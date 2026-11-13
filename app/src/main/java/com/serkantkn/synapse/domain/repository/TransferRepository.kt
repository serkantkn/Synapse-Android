package com.serkantkn.synapse.domain.repository

import com.serkantkn.synapse.domain.model.Packet
import kotlinx.coroutines.flow.Flow

/**
 * Interface for data transfer operations over the established connection.
 */
interface TransferRepository {
    /** Start the TCP server and listen for incoming connections. Returns the port. */
    suspend fun startServer(): Int

    /** Connect to a remote device at the given address and port. */
    suspend fun connectTo(ipAddress: String, port: Int)

    /** Send a packet to the connected device. */
    suspend fun sendPacket(packet: Packet)

    /** Receive incoming packets as a flow. */
    fun receivePackets(): Flow<Packet>

    /** Close the active peer connection (server keeps listening). */
    fun disconnect()

    /** Close the peer connection and stop listening. */
    fun stopServer()
}
