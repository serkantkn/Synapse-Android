package com.serkantkn.synapse.core.util

/**
 * Synapse-wide constants.
 */
object Constants {
    const val PAIRING_CODE_LENGTH = 6
    const val HEARTBEAT_INTERVAL_MS = 5000L
    const val SERVICE_TYPE = "_synapse._tcp."
}

/**
 * Generates a random numeric pairing code.
 */
fun generatePairingCode(): String {
    return (100000..999999).random().toString()
}
