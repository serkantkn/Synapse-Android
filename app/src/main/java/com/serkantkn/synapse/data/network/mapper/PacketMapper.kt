package com.serkantkn.synapse.data.network.mapper

import com.serkantkn.synapse.data.dto.PacketDto
import com.serkantkn.synapse.domain.model.Packet
import com.serkantkn.synapse.domain.model.PacketType

/**
 * Maps between the network DTO layer and the domain model layer.
 */
object PacketMapper {

    fun toDto(packet: Packet): PacketDto = PacketDto(
        packetId = packet.packetId,
        type = packet.type.name,
        senderId = packet.senderId,
        timestamp = packet.timestamp,
        payload = packet.payload
    )

    /**
     * Returns null when the packet can't be represented in the domain model
     * (unknown type, or required fields that Gson left null despite Kotlin's types).
     */
    fun toDomain(dto: PacketDto): Packet? {
        // Gson bypasses Kotlin null-safety, so check the "non-null" fields explicitly.
        val typeName: String? = dto.type
        val packetId: String? = dto.packetId
        val senderId: String? = dto.senderId
        if (typeName == null || packetId == null || senderId == null) return null
        val type = PacketType.entries.firstOrNull { it.name == typeName } ?: return null
        val payload: Map<String, Any?>? = dto.payload
        return Packet(
            packetId = packetId,
            type = type,
            senderId = senderId,
            timestamp = dto.timestamp,
            payload = payload ?: emptyMap()
        )
    }
}
