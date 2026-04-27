package com.serkantkn.synapse.domain.model

/**
 * Represents a discovered device on the local network.
 */
data class Device(
    val id: String,
    val name: String,
    val deviceType: String, // "macOS" or "android"
    val ipAddress: String,
    val port: Int
)

/**
 * Connection lifecycle states.
 */
enum class ConnectionStatus {
    DISCONNECTED,
    DISCOVERING,
    PAIRING,
    CONNECTED,
    ERROR
}
