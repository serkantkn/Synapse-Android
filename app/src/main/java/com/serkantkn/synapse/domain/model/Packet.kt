package com.serkantkn.synapse.domain.model

/**
 * Represents packet types exchanged between devices.
 */
enum class PacketType {
    HANDSHAKE_REQUEST,
    HANDSHAKE_RESPONSE,
    CLIPBOARD_TRANSFER,
    HEARTBEAT,
    CALL_EVENT,
    SMS_EVENT,
    CALL_COMMAND,
    SMS_COMMAND,
    WALLPAPER_TRANSFER,
    NOTIFICATION_EVENT,
    SCREEN_STREAM_COMMAND,
    SCREEN_FRAME,
    REMOTE_INPUT
}

/**
 * Base packet envelope for all network communication.
 */
data class Packet(
    val packetId: String,
    val type: PacketType,
    val senderId: String,
    val timestamp: Long,
    val payload: Map<String, Any?>
)
