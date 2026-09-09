package ph.bulig.mesh.crypto

import ph.bulig.mesh.model.MeshPacket

/**
 * Signs packets this device originates.
 *
 * Only the origin signs. A relay carries the MAC opaquely — it holds no other
 * device's key — so an intermediate phone can transport a report it cannot
 * forge or alter.
 *
 * A device that has never registered has no key, and [sign] returns the packet
 * unsigned rather than failing. Requiring registration before reporting would
 * reintroduce the very internet dependency this architecture exists to remove;
 * the server records such packets as unverifiable rather than rejecting them.
 */
class PacketSigner(private val deviceKey: ByteArray?) {

    val canSign: Boolean get() = deviceKey != null

    fun sign(packet: MeshPacket): MeshPacket {
        val key = deviceKey ?: return packet
        return packet.copy(hmac = CanonicalPacket.sign(packet, key))
    }

    /**
     * Verification is a server-side concern; a relay cannot do it. Exposed only
     * so a device can check its own outbound packets in tests and diagnostics.
     */
    fun verifyOwn(packet: MeshPacket): Boolean {
        val key = deviceKey ?: return false
        val hmac = packet.hmac ?: return false
        return CanonicalPacket.verify(packet, key, hmac)
    }
}
