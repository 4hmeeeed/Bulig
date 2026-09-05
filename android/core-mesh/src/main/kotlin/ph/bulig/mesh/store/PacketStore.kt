package ph.bulig.mesh.store

import ph.bulig.mesh.model.MeshPacket
import ph.bulig.mesh.model.PacketId

/**
 * Local persistence for packets this device is carrying.
 *
 * On-device this is backed by Room; in tests by a map. The engine never talks to
 * a database directly, which is what keeps it testable without Android.
 */
interface PacketStore {
    fun put(packet: MeshPacket)
    fun get(id: PacketId): MeshPacket?
    fun all(): List<MeshPacket>

    /** Packets still eligible to be handed to a peer. */
    fun forwardable(): List<MeshPacket>

    /** Packets not yet confirmed as delivered to the server. */
    fun pendingSync(): List<MeshPacket>

    fun markSynced(id: PacketId)
    fun remove(id: PacketId)
    fun size(): Int
}

class InMemoryPacketStore : PacketStore {
    private val packets = LinkedHashMap<PacketId, MeshPacket>()
    private val synced = HashSet<PacketId>()

    override fun put(packet: MeshPacket) {
        packets[packet.packetId] = packet
    }

    override fun get(id: PacketId): MeshPacket? = packets[id]

    override fun all(): List<MeshPacket> = packets.values.toList()

    override fun forwardable(): List<MeshPacket> =
        packets.values.filter { !it.isTerminal }

    override fun pendingSync(): List<MeshPacket> =
        packets.values.filter { it.packetId !in synced }

    override fun markSynced(id: PacketId) {
        synced.add(id)
    }

    override fun remove(id: PacketId) {
        packets.remove(id)
    }

    override fun size(): Int = packets.size

    fun isSynced(id: PacketId): Boolean = id in synced
}
