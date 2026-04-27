package com.serkantkn.synapse.domain.repository

import com.serkantkn.synapse.domain.model.Device
import kotlinx.coroutines.flow.Flow

/**
 * Interface for network service discovery operations.
 */
interface DiscoveryRepository {
    /** Register this device as a Synapse service on the network. */
    fun registerService(port: Int)

    /** Stop advertising this device. */
    fun unregisterService()

    /** Start discovering other Synapse devices. Returns a flow of discovered devices. */
    fun discoverDevices(): Flow<List<Device>>

    /** Stop the discovery process. */
    fun stopDiscovery()
}
