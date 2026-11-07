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
    WALLPAPER_REQUEST,
    NOTIFICATION_EVENT,
    SCREEN_STREAM_COMMAND,
    SCREEN_FRAME,
    REMOTE_INPUT,
    FILE_TRANSFER,
    PHOTO_LIBRARY_REQ,
    PHOTO_LIBRARY_RESP,
    PHOTO_DOWNLOAD_REQ,
    PHOTO_DOWNLOAD_RESP,
    PHOTO_DELETE_REQ,
    // iOS Sync
    CONTACTS_REQUEST,
    CONTACTS_RESPONSE,
    PHOTOS_REQUEST,
    PHOTOS_RESPONSE,
    FILES_REQUEST,
    FILES_RESPONSE,
    FILE_DOWNLOAD_REQ,
    FILE_DOWNLOAD_RESP
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
