package ph.bulig.mesh

import ph.bulig.mesh.crypto.PacketSigner
import ph.bulig.mesh.digest.BloomDigest
import ph.bulig.mesh.model.DeviceId
import ph.bulig.mesh.model.EmergencyId
import ph.bulig.mesh.model.EmergencyPayload
import ph.bulig.mesh.model.MeshPacket
import ph.bulig.mesh.model.PacketId
import ph.bulig.mesh.policy.ForwardDecision
import ph.bulig.mesh.policy.ForwardingPolicy
import ph.bulig.mesh.policy.RelayConditions
import ph.bulig.mesh.store.PacketStore
import ph.bulig.mesh.store.SeenSet
import ph.bulig.mesh.transport.MeshEvent
import ph.bulig.mesh.transport.MeshTransport
import ph.bulig.mesh.transport.PeerHandle

/**
 * One phone's participation in the Bulig mesh.
 *
 * This is the project's contribution in a single class: create a report with no
 * network, hold it, hand it to whoever comes within range, and keep holding it
 * until something acknowledges it. It knows nothing about Bluetooth — the radio
 * lives behind [MeshTransport].
 *
 * @see docs/06-ble-protocol.md
 */
class MeshNode(
    val deviceId: DeviceId,
    private val store: PacketStore,
    private val seen: SeenSet,
    private val transport: MeshTransport,
    private val clock: Clock = Clock.SYSTEM,
    private val signer: PacketSigner = PacketSigner(null),
    private val policy: ForwardingPolicy = ForwardingPolicy(),
    private val onEvent: (MeshEvent) -> Unit = {},
) {

    /** Peers already handed a given packet, so one session never repeats itself. */
    private val sentThisSession = HashMap<PacketId, MutableSet<DeviceId>>()

    var batteryPercent: Int = 100

    /**
     * Creates a report on this device. No network is involved, and none is
     * required — this is what the resident sees confirmed.
     */
    fun createEmergency(
        packetId: PacketId,
        emergencyId: EmergencyId,
        payload: EmergencyPayload,
        ttl: Int = MeshPacket.DEFAULT_TTL,
    ): MeshPacket {
        val packet = signer.sign(
            MeshPacket(
                packetId = packetId,
                emergencyId = emergencyId,
                originDeviceId = deviceId,
                createdAtDeviceMs = clock.nowMs(),
                payload = payload,
                ttlRemaining = ttl,
                ttlInitial = ttl,
            )
        )

        store.put(packet)
        seen.add(packetId)
        onEvent(MeshEvent.Created(packetId))

        return packet
    }

    /**
     * Handles a packet offered by a peer.
     *
     * The dedup check comes first and is keyed on the packet id the ORIGIN
     * minted — which is exactly why that id must never be rewritten in transit.
     * A packet that has looped back around is recognised here and dropped.
     *
     * Note that a packet arriving at TTL 0 is still STORED. It merely stops
     * being forwarded. Discarding it would throw away a report that has already
     * been carried across the barangay and may yet reach the server from here.
     */
    fun onPacketReceived(incoming: MeshPacket): ReceiveOutcome {
        if (seen.contains(incoming.packetId)) {
            onEvent(MeshEvent.DuplicateSuppressed(incoming.packetId))
            return ReceiveOutcome.Duplicate
        }

        val relayed = incoming.relayedTo(deviceId)

        store.put(relayed)
        seen.add(relayed.packetId)

        return if (relayed.isTerminal) {
            onEvent(MeshEvent.TtlExpired(relayed.packetId))
            ReceiveOutcome.AcceptedTerminal(relayed)
        } else {
            onEvent(MeshEvent.Received(relayed.packetId, relayed.hopCount, relayed.ttlRemaining))
            ReceiveOutcome.Accepted(relayed)
        }
    }

    /**
     * One relay pass: find peers, ask what they already hold, and offer only
     * what they are missing.
     *
     * Peers with connectivity are served first — they are the ones that can end
     * a packet's journey rather than merely extend it.
     */
    fun relayOnce(): RelayReport {
        val peers = transport.discoverPeers()
            .sortedByDescending { it.hasInternet }

        var forwarded = 0
        var skipped = 0

        for (peer in peers) {
            val digest = transport.requestDigest(peer) ?: BloomDigest.empty()
            val conditions = RelayConditions(batteryPercent, clock.nowMs())

            for (packet in store.forwardable()) {
                val decision = policy.decide(
                    packet = packet,
                    peer = peer.deviceId,
                    peerHolds = digest::mightContain,
                    alreadySentThisSession = sentThisSession[packet.packetId]
                        ?.contains(peer.deviceId) == true,
                    conditions = conditions,
                )

                if (decision !is ForwardDecision.Forward) {
                    skipped++
                    onEvent(
                        MeshEvent.ForwardSkipped(
                            packet.packetId,
                            peer.deviceId,
                            (decision as ForwardDecision.Skip).reason,
                        )
                    )
                    continue
                }

                if (transport.send(peer, packet)) {
                    forwarded++
                    sentThisSession.getOrPut(packet.packetId) { mutableSetOf() }
                        .add(peer.deviceId)
                    onEvent(MeshEvent.Forwarded(packet.packetId, peer.deviceId))
                }
            }
        }

        return RelayReport(peersSeen = peers.size, forwarded = forwarded, skipped = skipped)
    }

    /** This device's digest, offered to peers before any payload changes hands. */
    fun digest(): BloomDigest = BloomDigest.of(store.all().map { it.packetId })

    /** Packets still awaiting server acknowledgement, TTL-expired ones included. */
    fun pendingSync(): List<MeshPacket> = store.pendingSync()

    fun markSynced(id: PacketId) = store.markSynced(id)

    /** Cleared when a peer goes out of range, so a later encounter may retry. */
    fun endSession(peer: DeviceId) {
        sentThisSession.values.forEach { it.remove(peer) }
    }

    fun heldPackets(): List<MeshPacket> = store.all()

    sealed interface ReceiveOutcome {
        data class Accepted(val packet: MeshPacket) : ReceiveOutcome

        /** Stored and syncable, but it will not be forwarded again. */
        data class AcceptedTerminal(val packet: MeshPacket) : ReceiveOutcome

        data object Duplicate : ReceiveOutcome
    }

    data class RelayReport(val peersSeen: Int, val forwarded: Int, val skipped: Int)
}
