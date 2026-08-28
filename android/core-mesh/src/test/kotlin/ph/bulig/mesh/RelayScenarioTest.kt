package ph.bulig.mesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ph.bulig.mesh.model.EmergencyId
import ph.bulig.mesh.model.EmergencyPayload
import ph.bulig.mesh.model.PacketId
import ph.bulig.mesh.transport.MeshEvent

/**
 * The proposal's controlled test scenarios, as automated tests.
 *
 * TESTS 2, 3, 4 and 5 from the capstone testing plan run here without radios or
 * phones. Field testing on real devices then measures the things only hardware
 * can tell us — range, interference, battery — while these prove the protocol
 * itself behaves, repeatably, every time CI runs.
 *
 * @see docs/10-testing-plan.md 10.1
 */
class RelayScenarioTest {

    private val payload = EmergencyPayload(
        typeCode = "MEDICAL",
        description = "Elderly man collapsed and is not responding.",
        affectedCount = 4,
        elderlyCount = 2,
        mobilityLimitedCount = 1,
        isLifeThreatening = true,
        latitude = 11.2447,
        longitude = 125.0038,
        accuracyM = 12.4,
        locationProvider = "gps",
    )

    private fun packetId(n: Int) = PacketId("00000000-0000-4000-8000-%012d".format(n))
    private fun emergencyId(n: Int) = EmergencyId("11111111-0000-4000-8000-%012d".format(n))

    /** TEST 2 — one-hop relay: A hands the report to B. */
    @Test
    fun `one hop relay delivers the packet to a neighbour`() {
        val mesh = VirtualMesh()
        mesh.addNode("A")
        mesh.addNode("B")
        mesh.link("A", "B")

        mesh.node("A").createEmergency(packetId(1), emergencyId(1), payload)
        mesh.runUntilQuiet()

        val received = mesh.store("B").get(packetId(1))
        assertNotNull(received, "B should be carrying A's report")
        assertEquals(1, received.hopCount)
        assertEquals(9, received.ttlRemaining)
    }

    /**
     * TEST 3 — multi-hop store-and-forward.
     *
     * This is the project's core claim: a report escapes a connectivity dead
     * zone by riding on phones that are themselves offline.
     */
    @Test
    fun `packet traverses a four node chain and arrives intact`() {
        val mesh = VirtualMesh()
        listOf("A", "B", "C", "D").forEach { mesh.addNode(it) }
        mesh.chain("A", "B", "C", "D")

        val original = mesh.node("A").createEmergency(packetId(1), emergencyId(1), payload)
        mesh.runUntilQuiet()

        val atD = mesh.store("D").get(packetId(1))
        assertNotNull(atD, "the report should have reached the far end of the chain")
        assertEquals(3, atD.hopCount, "D is three hops from A")
        assertEquals(7, atD.ttlRemaining, "TTL decrements once per hop")

        // Identity and content survive the journey: relays carry, they do not author.
        assertEquals(original.packetId, atD.packetId)
        assertEquals(original.emergencyId, atD.emergencyId)
        assertEquals(original.originDeviceId, atD.originDeviceId)
        assertEquals(original.payload, atD.payload)
        assertEquals(original.hmac, atD.hmac)
        assertEquals(original.createdAtDeviceMs, atD.createdAtDeviceMs)
    }

    /**
     * TEST 4 — duplicate detection, first line of defence.
     *
     * C can hear A directly AND through B, so the same report reaches it by two
     * routes. The digest exchange means the redundant copy is never transmitted
     * at all: a peer that already holds a packet is not offered it again.
     *
     * Suppressing the transfer beats suppressing the stored copy — on a real
     * mesh that is airtime and battery not spent.
     */
    @Test
    fun `a packet reaching a node by two routes is never sent twice`() {
        val mesh = VirtualMesh()
        listOf("A", "B", "C").forEach { mesh.addNode(it) }
        mesh.link("A", "B")
        mesh.link("B", "C")
        mesh.link("A", "C") // the second path

        mesh.node("A").createEmergency(packetId(1), emergencyId(1), payload)
        mesh.runUntilQuiet()

        assertEquals(1, mesh.store("C").size(), "C must hold exactly one packet")
        assertEquals(1, mesh.distinctEmergencies(), "one report, however many routes it took")

        val skipped = mesh.events
            .map { it.second }
            .filterIsInstance<MeshEvent.ForwardSkipped>()
        assertTrue(
            skipped.any { it.reason.contains("already holds") },
            "the anti-entropy digest should have prevented the redundant transfer; saw $skipped",
        )
    }

    /**
     * TEST 4 — duplicate detection, second line of defence.
     *
     * The digest is a Bloom filter, and a peer may also be mid-transfer when a
     * second copy arrives, so a duplicate CAN still reach a node. The seen-set
     * is what catches it there.
     *
     * Delivered directly rather than through the mesh, because the digest layer
     * is deliberately good at making this situation rare — and the failure mode
     * being guarded against is the one where it slips through anyway.
     */
    @Test
    fun `a duplicate that reaches a node anyway is recognised and dropped`() {
        val mesh = VirtualMesh()
        mesh.addNode("A")
        mesh.addNode("B")
        mesh.link("A", "B")

        val packet = mesh.node("A").createEmergency(packetId(1), emergencyId(1), payload)

        val first = mesh.node("B").onPacketReceived(packet)
        val second = mesh.node("B").onPacketReceived(packet)

        assertTrue(first is MeshNode.ReceiveOutcome.Accepted, "the first copy is new to B")
        assertTrue(second is MeshNode.ReceiveOutcome.Duplicate, "the second must be recognised")

        assertEquals(1, mesh.store("B").size(), "B stores one copy, not two")
        assertEquals(
            1,
            mesh.store("B").get(packetId(1))!!.hopCount,
            "a suppressed duplicate must not inflate the hop count",
        )
        assertEquals(
            1,
            mesh.countEvents { it is MeshEvent.DuplicateSuppressed },
            "exactly one suppression should be recorded, for the evaluation metrics",
        )
    }

    /**
     * TEST 5 — TTL expiry.
     *
     * A packet must stop propagating, or a dense crowd of phones becomes a
     * broadcast storm.
     */
    @Test
    fun `forwarding halts when ttl is exhausted`() {
        val mesh = VirtualMesh()
        listOf("A", "B", "C", "D", "E").forEach { mesh.addNode(it) }
        mesh.chain("A", "B", "C", "D", "E")

        // TTL 2 allows exactly two hops: A to B, then B to C.
        mesh.node("A").createEmergency(packetId(1), emergencyId(1), payload, ttl = 2)
        mesh.runUntilQuiet()

        assertNotNull(mesh.store("B").get(packetId(1)), "first hop is within TTL")
        assertNotNull(mesh.store("C").get(packetId(1)), "second hop is within TTL")
        assertNull(mesh.store("D").get(packetId(1)), "TTL is exhausted; D must never see it")
        assertNull(mesh.store("E").get(packetId(1)))

        val atC = mesh.store("C").get(packetId(1))!!
        assertEquals(0, atC.ttlRemaining)
        assertTrue(atC.isTerminal)
    }

    /**
     * A TTL-expired packet is still SYNCABLE.
     *
     * Dropping it would throw away a report that has already been carried across
     * the barangay and might yet reach the server from where it sits.
     */
    @Test
    fun `a ttl expired packet is still stored and still pending sync`() {
        val mesh = VirtualMesh()
        listOf("A", "B", "C").forEach { mesh.addNode(it) }
        mesh.chain("A", "B", "C")

        mesh.node("A").createEmergency(packetId(1), emergencyId(1), payload, ttl = 2)
        mesh.runUntilQuiet()

        val terminal = mesh.node("C")
        assertTrue(
            terminal.pendingSync().any { it.packetId == packetId(1) },
            "a packet that can no longer be relayed must still be uploadable",
        )
        assertTrue(mesh.store("C").forwardable().none { it.packetId == packetId(1) })
    }

    /** A ring is the topology that would circulate forever without a seen-set. */
    @Test
    fun `a ring topology converges instead of circulating forever`() {
        val mesh = VirtualMesh()
        listOf("A", "B", "C", "D").forEach { mesh.addNode(it) }
        mesh.ring("A", "B", "C", "D")

        mesh.node("A").createEmergency(packetId(1), emergencyId(1), payload)
        val rounds = mesh.runUntilQuiet(maxRounds = 30)

        assertTrue(rounds < 30, "the mesh must settle rather than loop until TTL burns out")
        assertEquals(4, mesh.holders(packetId(1)).size, "every node ends up holding one copy")
        assertEquals(1, mesh.distinctEmergencies())
        mesh.holders(packetId(1)).forEach { name ->
            assertEquals(1, mesh.store(name).size(), "$name should hold exactly one packet")
        }
    }

    /** Several reports in flight at once must not interfere with one another. */
    @Test
    fun `concurrent emergencies from different origins all propagate`() {
        val mesh = VirtualMesh()
        listOf("A", "B", "C", "D").forEach { mesh.addNode(it) }
        mesh.chain("A", "B", "C", "D")

        mesh.node("A").createEmergency(packetId(1), emergencyId(1), payload)
        mesh.node("D").createEmergency(packetId(2), emergencyId(2), payload.copy(typeCode = "FIRE"))
        mesh.runUntilQuiet()

        assertNotNull(mesh.store("D").get(packetId(1)), "A's report reaches D")
        assertNotNull(mesh.store("A").get(packetId(2)), "D's report reaches A")
        assertEquals(2, mesh.distinctEmergencies())
    }

    /**
     * A lossy 20-node mesh. Delivery is opportunistic, so the assertion is not
     * "everything arrives" — it is that nothing is ever duplicated or corrupted,
     * however many transfers fail.
     */
    @Test
    fun `a lossy twenty node mesh never produces a duplicate emergency`() {
        val mesh = VirtualMesh(lossRate = 0.30, seed = 7)
        val names = (1..20).map { "N$it" }
        names.forEach { mesh.addNode(it) }
        mesh.chain(*names.toTypedArray())
        // Some cross-links, so the topology is a mesh rather than a line.
        mesh.link("N1", "N5"); mesh.link("N4", "N12"); mesh.link("N9", "N17")

        mesh.node("N1").createEmergency(packetId(1), emergencyId(1), payload)
        mesh.runUntilQuiet(maxRounds = 40)

        assertEquals(1, mesh.distinctEmergencies(), "loss must never fork a report into two")

        val holders = mesh.holders(packetId(1))
        assertTrue(holders.size > 1, "with 30% loss the report should still spread; got $holders")
        holders.forEach { assertEquals(1, mesh.store(it).size(), "$it holds one copy") }

        // Whatever route each copy took, TTL accounting must never go backwards.
        holders.forEach { name ->
            val p = mesh.store(name).get(packetId(1))!!
            assertEquals(p.ttlInitial - p.hopCount, p.ttlRemaining, "TTL and hops disagree at $name")
        }
    }

    /** The origin should not be handed back its own report. */
    @Test
    fun `a packet is not relayed back to the device that created it`() {
        val mesh = VirtualMesh()
        mesh.addNode("A")
        mesh.addNode("B")
        mesh.link("A", "B")

        mesh.node("A").createEmergency(packetId(1), emergencyId(1), payload)
        mesh.runUntilQuiet()

        val skipped = mesh.eventsOf("B").filterIsInstance<MeshEvent.ForwardSkipped>()
        assertTrue(
            skipped.any { it.reason.contains("came from") },
            "B should decline to send A's own packet back to A; saw $skipped",
        )
        assertEquals(1, mesh.store("A").size(), "A still holds exactly its own packet")
    }

    /** Relaying pauses before the carrier's own phone is too flat to call for help. */
    @Test
    fun `a node below the battery floor stops relaying`() {
        val mesh = VirtualMesh()
        listOf("A", "B", "C").forEach { mesh.addNode(it) }
        mesh.chain("A", "B", "C")

        mesh.node("A").createEmergency(packetId(1), emergencyId(1), payload)
        mesh.node("B").batteryPercent = 5

        mesh.runUntilQuiet()

        assertNotNull(mesh.store("B").get(packetId(1)), "B still accepts and carries the report")
        assertNull(
            mesh.store("C").get(packetId(1)),
            "but B must not spend its remaining battery relaying onward",
        )
    }

    /** Reports too old to act on should stop consuming airtime. */
    @Test
    fun `a packet older than the relay age limit is not forwarded`() {
        val mesh = VirtualMesh()
        listOf("A", "B", "C").forEach { mesh.addNode(it) }
        mesh.link("A", "B")

        mesh.node("A").createEmergency(packetId(1), emergencyId(1), payload)
        mesh.runUntilQuiet()
        assertNotNull(mesh.store("B").get(packetId(1)))

        // B meets C only a day and a half later.
        mesh.advanceTime(36L * 60 * 60 * 1000)
        mesh.link("B", "C")
        mesh.runUntilQuiet()

        assertNull(mesh.store("C").get(packetId(1)), "a 36-hour-old report should not still be spreading")
    }

    /** Synced packets stay held for dedup, but are no longer pending upload. */
    @Test
    fun `marking a packet synced removes it from the pending queue`() {
        val mesh = VirtualMesh()
        mesh.addNode("A")

        mesh.node("A").createEmergency(packetId(1), emergencyId(1), payload)
        assertEquals(1, mesh.node("A").pendingSync().size)

        mesh.node("A").markSynced(packetId(1))

        assertEquals(0, mesh.node("A").pendingSync().size)
        assertNotNull(mesh.store("A").get(packetId(1)), "still held, so a re-delivery is recognised")
    }
}
