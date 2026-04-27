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

    fun toDomain(dto: PacketDto): Packet = Packet(
        packetId = dto.packetId,
        type = PacketType.valueOf(dto.type),
        senderId = dto.senderId,
        timestamp = dto.timestamp,
        payload = dto.payload
    )
}
