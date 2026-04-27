package com.serkantkn.synapse.data.dto

import com.google.gson.annotations.SerializedName

/**
 * JSON-serializable Data Transfer Object for network packets.
 * Maps directly to the protocol's JSON schema.
 */
data class PacketDto(
    @SerializedName("packet_id") val packetId: String,
    @SerializedName("type") val type: String,
    @SerializedName("sender_id") val senderId: String,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("payload") val payload: Map<String, Any?>
)
