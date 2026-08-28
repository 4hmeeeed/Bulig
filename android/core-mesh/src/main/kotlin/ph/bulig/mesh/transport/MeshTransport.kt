package ph.bulig.mesh.transport

import ph.bulig.mesh.digest.BloomDigest
import ph.bulig.mesh.model.DeviceId
import ph.bulig.mesh.model.MeshPacket

/**
 * A peer currently within range.
 *
 * [hasInternet] is advertised in four bytes of BLE manufacturer data, and is why
 * a scanning device can prefer a peer that can actually reach the server — a
 * one-byte optimisation with an outsized effect on delivery time.
 */
data class PeerHandle(
    val deviceId: DeviceId,
    val hasInternet: Boolean = false,
    val pendingCount: Int = 0,
    val protocolVersion: Int = 1,
)

/**
 * The mesh's link layer, kept behind an interface.
 *
 * On-device this is implemented over BLE GATT. In tests, VirtualMesh implements
 * it in memory. Bluetooth cannot be emulated, but relay LOGIC does not need a
 * radio — which is precisely what makes proposal TESTS 3, 4 and 5 automatable.
 *
 * @see docs/06-ble-protocol.md 6.9
 */
interface MeshTransport {
    /** Peers currently discoverable. */
    fun discoverPeers(): List<PeerHandle>

    /** The peer's Bloom digest of packet ids it already holds. */
    fun requestDigest(peer: PeerHandle): BloomDigest?

    /**
     * Hands one packet to a peer. Returns true when the peer acknowledged
     * storing it. Delivery is opportunistic: false is an ordinary outcome, not
     * an error.
     */
    fun send(peer: PeerHandle, packet: MeshPacket): Boolean
}

/** Observable outcomes, for logging and the Mesh Status screen. */
sealed interface MeshEvent {
    val packetId: ph.bulig.mesh.model.PacketId

    data class Created(override val packetId: ph.bulig.mesh.model.PacketId) : MeshEvent
    data class Received(
        override val packetId: ph.bulig.mesh.model.PacketId,
        val hopCount: Int,
        val ttlRemaining: Int,
    ) : MeshEvent

    data class DuplicateSuppressed(override val packetId: ph.bulig.mesh.model.PacketId) : MeshEvent
    data class TtlExpired(override val packetId: ph.bulig.mesh.model.PacketId) : MeshEvent
    data class Forwarded(
        override val packetId: ph.bulig.mesh.model.PacketId,
        val to: DeviceId,
    ) : MeshEvent

    data class ForwardSkipped(
        override val packetId: ph.bulig.mesh.model.PacketId,
        val to: DeviceId,
        val reason: String,
    ) : MeshEvent
}
