package ph.bulig.app.ble

import ph.bulig.data.model.LocalReport
import ph.bulig.data.store.ReportStore
import ph.bulig.mesh.model.DeviceId
import ph.bulig.mesh.model.MeshPacket
import ph.bulig.mesh.model.PacketId
import ph.bulig.mesh.store.PacketStore

/**
 * Lets the mesh engine write into the app's real, encrypted store.
 *
 * Two interfaces exist here for a reason worth stating: `:core-mesh` knows only
 * about [MeshPacket] — the thing that travels — while `:data` knows about
 * [LocalReport], which is a packet plus the local bookkeeping a resident's
 * screens need. Merging them would drag delivery states and sync bookkeeping
 * into the relay engine, and `:core-mesh` would stop being testable as a
 * protocol in isolation.
 *
 * So this adapter is where a packet arriving from a peer becomes a report this
 * phone is carrying.
 *
 * @param ownDeviceId used to decide whether an arriving packet is one of this
 *   resident's own coming back through the mesh, or a neighbour's being carried.
 *   Getting this wrong would file somebody else's emergency under "My reports".
 */
class StoreBackedPacketStore(
    private val reports: ReportStore,
    private val ownDeviceId: DeviceId? = null,
) : PacketStore {

    /**
     * Preserves local bookkeeping on a packet we already hold.
     *
     * A relayed copy arriving again carries a higher hop count but knows nothing
     * about this phone's delivery state, emergency code, or handoffs. Replacing
     * the row wholesale would erase a confirmed delivery and tell the resident
     * their report was back to sitting on the phone.
     */
    override fun put(packet: MeshPacket) {
        val existing = reports.get(packet.packetId)

        reports.upsert(
            existing?.copy(packet = packet)
                ?: LocalReport(
                    packet = packet,
                    isMine = ownDeviceId != null && packet.originDeviceId == ownDeviceId,
                )
        )
    }

    override fun get(id: PacketId): MeshPacket? = reports.get(id)?.packet

    override fun all(): List<MeshPacket> = reports.all().map { it.packet }

    /**
     * A packet at TTL 0 stops being offered to peers — but it is still stored
     * and still syncable, which is why this filter is separate from deletion.
     */
    override fun forwardable(): List<MeshPacket> =
        reports.all().map { it.packet }.filterNot { it.isTerminal }

    override fun pendingSync(): List<MeshPacket> = reports.pendingSync().map { it.packet }

    override fun markSynced(id: PacketId) {
        reports.get(id)?.let { reports.upsert(it.copy(synced = true)) }
    }

    override fun remove(id: PacketId) = reports.delete(id)

    override fun size(): Int = reports.count()
}
