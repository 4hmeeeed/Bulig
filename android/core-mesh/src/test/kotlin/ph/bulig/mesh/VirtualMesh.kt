package ph.bulig.mesh

import kotlin.random.Random
import ph.bulig.mesh.crypto.PacketSigner
import ph.bulig.mesh.digest.BloomDigest
import ph.bulig.mesh.model.DeviceId
import ph.bulig.mesh.model.MeshPacket
import ph.bulig.mesh.policy.ForwardingPolicy
import ph.bulig.mesh.store.InMemoryPacketStore
import ph.bulig.mesh.store.InMemorySeenSet
import ph.bulig.mesh.transport.MeshEvent
import ph.bulig.mesh.transport.MeshTransport
import ph.bulig.mesh.transport.PeerHandle

/**
 * A mesh of phones, in memory.
 *
 * Bluetooth cannot be emulated, but relay LOGIC does not need a radio. Wiring
 * [MeshTransport] to an in-memory neighbour table lets the proposal's multi-hop,
 * duplicate, and TTL scenarios run as ordinary unit tests in milliseconds —
 * deterministically, on any machine, with no phones and no Android SDK.
 *
 * Real devices then validate the radio. This validates the protocol.
 *
 * @see docs/06-ble-protocol.md 6.9
 */
class VirtualMesh(
    private val clock: MutableClock = MutableClock(1_700_000_000_000),
    /** Probability that any single transfer is dropped, as BLE transfers are. */
    private val lossRate: Double = 0.0,
    seed: Int = 42,
) {

    private val random = Random(seed)
    private val nodes = LinkedHashMap<DeviceId, MeshNode>()
    private val stores = HashMap<DeviceId, InMemoryPacketStore>()

    /** Who can hear whom. Adjacency is directional so asymmetry can be modelled. */
    private val links = HashMap<DeviceId, MutableSet<DeviceId>>()

    private val internetCapable = HashSet<DeviceId>()
    val events = mutableListOf<Pair<DeviceId, MeshEvent>>()

    fun node(name: String): MeshNode = nodes.getValue(DeviceId(name))

    fun store(name: String): InMemoryPacketStore = stores.getValue(DeviceId(name))

    fun addNode(
        name: String,
        hasInternet: Boolean = false,
        key: ByteArray? = null,
        policy: ForwardingPolicy = ForwardingPolicy(),
    ): MeshNode {
        val id = DeviceId(name)
        val store = InMemoryPacketStore()
        stores[id] = store

        if (hasInternet) internetCapable.add(id)

        val node = MeshNode(
            deviceId = id,
            store = store,
            seen = InMemorySeenSet(),
            transport = VirtualTransport(id),
            clock = clock,
            signer = PacketSigner(key),
            policy = policy,
            onEvent = { events += id to it },
        )

        nodes[id] = node
        links.getOrPut(id) { mutableSetOf() }
        return node
    }

    /** Puts two devices in range of each other. */
    fun link(a: String, b: String) {
        links.getOrPut(DeviceId(a)) { mutableSetOf() }.add(DeviceId(b))
        links.getOrPut(DeviceId(b)) { mutableSetOf() }.add(DeviceId(a))
    }

    /** A→B→C→…: the canonical relay chain. */
    fun chain(vararg names: String) {
        names.toList().zipWithNext { a, b -> link(a, b) }
    }

    /** A closed loop — the topology that would circulate forever without dedup. */
    fun ring(vararg names: String) {
        chain(*names)
        if (names.size > 2) link(names.last(), names.first())
    }

    /**
     * Runs relay passes until nothing more moves, or [maxRounds] is reached.
     *
     * Convergence rather than a fixed round count: a packet spreading through a
     * chain needs one round per hop, and hard-coding that in each test would
     * hide the very behaviour under test.
     */
    fun runUntilQuiet(maxRounds: Int = 20): Int {
        repeat(maxRounds) { round ->
            var moved = 0
            // Snapshot: nodes gain packets mid-round, and a packet must not
            // sprint the whole chain in a single pass.
            nodes.values.toList().forEach { moved += it.relayOnce().forwarded }
            if (moved == 0) return round + 1
        }
        return maxRounds
    }

    fun advanceTime(millis: Long) = clock.advanceBy(millis)

    fun nowMs(): Long = clock.nowMs()

    fun eventsOf(name: String): List<MeshEvent> =
        events.filter { it.first == DeviceId(name) }.map { it.second }

    fun countEvents(predicate: (MeshEvent) -> Boolean): Int =
        events.count { predicate(it.second) }

    /** How many distinct emergencies exist mesh-wide — must never exceed what was created. */
    fun distinctEmergencies(): Int =
        stores.values.flatMap { store -> store.all().map { it.emergencyId } }.toSet().size

    fun holders(packetId: ph.bulig.mesh.model.PacketId): List<String> =
        stores.filterValues { it.get(packetId) != null }.keys.map { it.value }

    /**
     * The transport as one device sees it.
     *
     * Deliberately does NOT reach into the receiving node's store directly: it
     * calls [MeshNode.onPacketReceived], so every test exercises the real
     * dedup, TTL and hop-count path rather than a shortcut.
     */
    private inner class VirtualTransport(private val self: DeviceId) : MeshTransport {

        override fun discoverPeers(): List<PeerHandle> =
            links[self].orEmpty().map { peer ->
                PeerHandle(
                    deviceId = peer,
                    hasInternet = peer in internetCapable,
                    pendingCount = stores[peer]?.size() ?: 0,
                )
            }

        override fun requestDigest(peer: PeerHandle): BloomDigest? =
            stores[peer.deviceId]?.let { store ->
                BloomDigest.of(store.all().map { it.packetId })
            }

        override fun send(peer: PeerHandle, packet: MeshPacket): Boolean {
            if (lossRate > 0.0 && random.nextDouble() < lossRate) return false

            val receiver = nodes[peer.deviceId] ?: return false
            val outcome = receiver.onPacketReceived(packet)

            // A duplicate still counts as delivered: the peer holds it, which is
            // what the sender needed. Reporting failure would make the sender
            // retry forever against a peer that is already satisfied.
            return outcome !is MeshNode.ReceiveOutcome.Duplicate ||
                stores[peer.deviceId]?.get(packet.packetId) != null
        }
    }
}
