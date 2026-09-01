package ph.bulig.mesh.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ph.bulig.mesh.digest.BloomDigest
import ph.bulig.mesh.framing.ChunkFraming
import ph.bulig.mesh.framing.ChunkReassembler
import ph.bulig.mesh.framing.ReassemblyResult
import ph.bulig.mesh.model.DeviceId
import ph.bulig.mesh.model.EmergencyId
import ph.bulig.mesh.model.EmergencyPayload
import ph.bulig.mesh.model.MeshPacket
import ph.bulig.mesh.model.PacketId

/**
 * One BLE encounter, driven without a Bluetooth stack.
 *
 * Every step of a real session is an asynchronous callback that can fail, arrive
 * out of order, or never arrive at all — which is exactly why this logic is
 * modelled as a state machine rather than left inside `BluetoothGattCallback`.
 * None of it could be exercised on a machine with no radio otherwise.
 *
 * @see docs/06-ble-protocol.md 6.3
 */
class BleSessionTest {

    private val peer = DeviceId("aaaaaaaa-0000-4000-8000-000000000001")

    private fun packet(n: Int, ttl: Int = 10, description: String? = null) = MeshPacket(
        packetId = PacketId("00000000-0000-4000-8000-%012d".format(n)),
        emergencyId = EmergencyId("11111111-0000-4000-8000-%012d".format(n)),
        originDeviceId = DeviceId("bbbbbbbb-0000-4000-8000-000000000002"),
        createdAtDeviceMs = 1_787_802_731_000,
        payload = EmergencyPayload(typeCode = "FLOOD", description = description),
        ttlRemaining = ttl,
        ttlInitial = 10,
    )

    private fun session(
        held: List<MeshPacket>,
        forwardable: (MeshPacket, DeviceId) -> Boolean = { _, _ -> true },
    ) = BleSession(held, forwardable)

    /** Drives a session to completion, answering every send with [ack]. */
    private fun run(
        session: BleSession,
        mtu: Int = 247,
        protocolVersion: Int = GattContract.PROTOCOL_VERSION,
        digest: BloomDigest? = BloomDigest.empty(),
        ack: (PacketId) -> AckCode = { AckCode.ACCEPTED },
    ): List<BleAction> {
        val actions = mutableListOf(session.start())

        var action = session.onEvent(BleEvent.MtuNegotiated(mtu))
        actions += action
        if (action is BleAction.Disconnect) return actions

        action = session.onEvent(BleEvent.NodeInfoRead(peer, protocolVersion))
        actions += action
        if (action is BleAction.Disconnect) return actions

        action = session.onEvent(BleEvent.DigestRead(digest))
        actions += action

        while (action is BleAction.SendPacket) {
            val id = (action as BleAction.SendPacket).packetId
            action = session.onEvent(BleEvent.PacketAcked(id, ack(id)))
            actions += action
        }

        return actions
    }

    // --- the happy path ---------------------------------------------------

    @Test
    fun `a session negotiates then identifies then compares before sending anything`() {
        val session = session(listOf(packet(1)))
        val actions = run(session)

        assertEquals(BleAction.RequestMtu, actions[0])
        assertEquals(BleAction.ReadNodeInfo, actions[1])
        assertEquals(
            BleAction.ReadDigest, actions[2],
            "no payload may move before the peer says what it already holds",
        )
        assertTrue(actions[3] is BleAction.SendPacket)
    }

    @Test
    fun `every held packet the peer lacks is delivered`() {
        val session = session(listOf(packet(1), packet(2), packet(3)))
        run(session)

        assertEquals(3, session.summary().deliveredCount)
        assertTrue(session.isFinished)
    }

    /** Anti-entropy: a packet the peer already has is never transmitted. */
    @Test
    fun `packets the peer already holds are skipped without being sent`() {
        val held = listOf(packet(1), packet(2), packet(3))
        val peerHas = BloomDigest.of(listOf(held[0].packetId, held[2].packetId))

        val session = session(held)
        val actions = run(session, digest = peerHas)

        val sent = actions.filterIsInstance<BleAction.SendPacket>()
        assertEquals(1, sent.size, "only the missing packet should go on the air")
        assertEquals(held[1].packetId, sent.single().packetId)
        assertEquals(2, session.summary().skipped)
    }

    @Test
    fun `packets the forwarding policy rejects are not offered`() {
        val session = session(
            held = listOf(packet(1), packet(2)),
            forwardable = { p, _ -> p.packetId == packet(1).packetId },
        )

        val actions = run(session)

        assertEquals(1, actions.filterIsInstance<BleAction.SendPacket>().size)
        assertEquals(1, session.summary().skipped)
    }

    /** More TTL means more journey left, so those gain most from a handoff. */
    @Test
    fun `packets with the most life left are sent first`() {
        val session = session(listOf(packet(1, ttl = 2), packet(2, ttl = 9), packet(3, ttl = 5)))

        val order = run(session).filterIsInstance<BleAction.SendPacket>()
            .map { it.packetId.value.takeLast(1) }

        assertEquals(listOf("2", "3", "1"), order)
    }

    // --- MTU --------------------------------------------------------------

    /** A peer granting less than requested is ordinary, not a failure. */
    @Test
    fun `a small mtu produces more fragments rather than an error`() {
        val long = packet(1, description = "x".repeat(600))

        val small = run(session(listOf(long)), mtu = 23)
            .filterIsInstance<BleAction.SendPacket>().single().frames.size
        val large = run(session(listOf(long)), mtu = 512)
            .filterIsInstance<BleAction.SendPacket>().single().frames.size

        assertTrue(small > large, "a 23-byte MTU must fragment more than a 512-byte one")
        assertTrue(large >= 1)
    }

    @Test
    fun `an mtu below the ble minimum is clamped rather than trusted`() {
        val session = session(listOf(packet(1)))
        session.start()
        session.onEvent(BleEvent.MtuNegotiated(4))
        session.onEvent(BleEvent.NodeInfoRead(peer, GattContract.PROTOCOL_VERSION))
        val action = session.onEvent(BleEvent.DigestRead(BloomDigest.empty()))

        // Would have thrown on a capacity of zero if the clamp were missing.
        assertTrue(action is BleAction.SendPacket)
        assertTrue((action as BleAction.SendPacket).frames.isNotEmpty())
    }

    // --- failure paths ----------------------------------------------------

    /** Framing for a version we cannot parse would corrupt the peer's buffers. */
    @Test
    fun `a peer speaking another protocol version is disconnected immediately`() {
        val session = session(listOf(packet(1)))
        val actions = run(session, protocolVersion = 99)

        val last = actions.last()
        assertTrue(last is BleAction.Disconnect)
        assertTrue((last as BleAction.Disconnect).reason.contains("v99"))
        assertEquals(0, session.summary().deliveredCount)
    }

    /**
     * A peer that will not answer is assumed to hold nothing. Re-sending costs
     * airtime; assuming it holds everything would cost a delivery.
     */
    @Test
    fun `an unreadable digest is treated as an empty one`() {
        val session = session(listOf(packet(1)))
        val actions = run(session, digest = null)

        assertEquals(1, actions.filterIsInstance<BleAction.SendPacket>().size)
        assertEquals(1, session.summary().deliveredCount)
    }

    @Test
    fun `a duplicate ack counts as delivered`() {
        val session = session(listOf(packet(1)))
        run(session) { AckCode.DUPLICATE }

        assertEquals(1, session.summary().deliveredCount, "the peer has it; that is the point")
        assertTrue(session.summary().failures.isEmpty())
    }

    @Test
    fun `a ttl-expired acceptance still counts as delivered`() {
        val session = session(listOf(packet(1)))
        run(session) { AckCode.ACCEPTED_TERMINAL }

        assertEquals(1, session.summary().deliveredCount)
    }

    @Test
    fun `a rejected packet is recorded without stopping the session`() {
        val session = session(listOf(packet(1), packet(2)))
        run(session) { id ->
            if (id == packet(1).packetId) AckCode.INVALID_HMAC else AckCode.ACCEPTED
        }

        val summary = session.summary()
        assertEquals(1, summary.deliveredCount)
        assertEquals(AckCode.INVALID_HMAC, summary.failures[packet(1).packetId])
    }

    /** A peer out of room will refuse everything; continuing wastes the battery. */
    @Test
    fun `a peer reporting no capacity ends the session early`() {
        val session = session(listOf(packet(1), packet(2), packet(3)))
        val actions = run(session) { AckCode.NO_CAPACITY }

        assertEquals(
            1, actions.filterIsInstance<BleAction.SendPacket>().size,
            "the session must stop after the first refusal, not try all three",
        )
        assertTrue(session.summary().endedBecause.contains("no capacity"))
    }

    /** Peers walk out of range constantly. That is ordinary on BLE. */
    @Test
    fun `a link failure mid-session ends it cleanly`() {
        val session = session(listOf(packet(1), packet(2)))
        session.start()
        session.onEvent(BleEvent.MtuNegotiated(247))
        session.onEvent(BleEvent.NodeInfoRead(peer, GattContract.PROTOCOL_VERSION))

        val action = session.onEvent(BleEvent.Failed("peer went out of range"))

        assertTrue(action is BleAction.Disconnect)
        assertTrue(session.isFinished)
        assertEquals("peer went out of range", session.summary().endedBecause)
    }

    @Test
    fun `a session with nothing to offer disconnects rather than idling`() {
        val session = session(emptyList())
        val actions = run(session)

        assertTrue(actions.last() is BleAction.Disconnect)
        assertTrue(session.isFinished)
    }

    // --- advertisement codec ----------------------------------------------

    @Test
    fun `an advertisement round trips`() {
        val original = AdvertisementPayload(
            hasInternet = true, supportsAdvertising = true, pendingCount = 1234,
        )

        val decoded = AdvertisementPayload.decode(original.encode())

        assertEquals(original, decoded)
        assertEquals(AdvertisementPayload.SIZE_BYTES, original.encode().size)
    }

    /** The one byte that lets a scanner prefer a peer that can end the journey. */
    @Test
    fun `the internet flag survives encoding`() {
        val online = AdvertisementPayload(hasInternet = true).encode()
        val offline = AdvertisementPayload(hasInternet = false).encode()

        assertTrue(AdvertisementPayload.decode(online)!!.hasInternet)
        assertFalse(AdvertisementPayload.decode(offline)!!.hasInternet)
    }

    @Test
    fun `a malformed advertisement decodes to null rather than nonsense`() {
        assertNull(AdvertisementPayload.decode(byteArrayOf(1, 2)))
        assertNotNull(AdvertisementPayload.decode(byteArrayOf(1, 0, 0, 0)))
    }

    @Test
    fun `an impossible pending count is clamped rather than wrapped`() {
        val decoded = AdvertisementPayload.decode(
            AdvertisementPayload(pendingCount = 999_999).encode()
        )

        assertEquals(0xFFFF, decoded!!.pendingCount)
    }

    // --- packet codec -----------------------------------------------------

    @Test
    fun `a packet survives encode and decode intact`() {
        val original = MeshPacket(
            packetId = PacketId("9b1d7c3e-4f2a-4c8b-9e1d-000000000001"),
            emergencyId = EmergencyId("44ca8e12-7b3d-4a5f-8c2e-000000000002"),
            originDeviceId = DeviceId("1f2e3d4c-5b6a-4798-8877-000000000003"),
            createdAtDeviceMs = 1_787_802_731_000,
            payload = EmergencyPayload(
                typeCode = "MEDICAL",
                description = "Elderly man collapsed near the creek/bridge — señor.",
                affectedCount = 4,
                elderlyCount = 2,
                mobilityLimitedCount = 1,
                isLifeThreatening = true,
                latitude = 11.2447,
                longitude = -125.0038,
                accuracyM = 12.4,
                locationProvider = "gps",
                capturedAtMs = 1_787_802_729_000,
            ),
            hmac = "f8c462f8b8f3d32fa09a8431202b448b",
            hopCount = 3,
            ttlRemaining = 7,
        )

        val decoded = PacketCodec.decode(PacketCodec.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `a packet with no optional fields round trips`() {
        val minimal = packet(1)

        assertEquals(minimal, PacketCodec.decode(PacketCodec.encode(minimal)))
    }

    /**
     * A mangled body is dropped rather than half-decoded. A report with a missing
     * id or a corrupted location is worse than no report, because it looks real
     * on an operator's screen.
     */
    @Test
    fun `a malformed body decodes to null`() {
        assertNull(PacketCodec.decode("not a packet".toByteArray()))
        assertNull(PacketCodec.decode(ByteArray(0)))
    }

    /** The full path: encode, fragment, reassemble, decode. */
    @Test
    fun `a packet survives fragmentation and reassembly end to end`() {
        val original = packet(1, description = "Tubig abot na sa hita. ".repeat(30))

        val frames = ChunkFraming.encode(PacketCodec.encode(original), mtu = 185)
        val reassembler = ChunkReassembler()

        var result: ReassemblyResult = ReassemblyResult.Incomplete
        frames.shuffled().forEach { result = reassembler.accept("peer", it, nowMs = 0) }

        assertTrue(result is ReassemblyResult.Complete)
        assertEquals(original, PacketCodec.decode((result as ReassemblyResult.Complete).body))
    }
}
