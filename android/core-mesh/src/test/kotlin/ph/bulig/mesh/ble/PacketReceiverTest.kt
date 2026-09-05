package ph.bulig.mesh.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ph.bulig.mesh.Clock
import ph.bulig.mesh.MeshNode
import ph.bulig.mesh.framing.ChunkFraming
import ph.bulig.mesh.model.DeviceId
import ph.bulig.mesh.model.EmergencyId
import ph.bulig.mesh.model.EmergencyPayload
import ph.bulig.mesh.model.MeshPacket
import ph.bulig.mesh.model.PacketId
import ph.bulig.mesh.store.InMemoryPacketStore
import ph.bulig.mesh.store.InMemorySeenSet
import ph.bulig.mesh.transport.MeshTransport
import ph.bulig.mesh.transport.PeerHandle

class PacketEnvelopeTest {

    private val body = "a realistic emergency body with a description".toByteArray()

    @Test
    fun `an envelope round-trips`() {
        assertTrue(PacketEnvelope.unwrap(PacketEnvelope.wrap(body)).contentEquals(body))
    }

    @Test
    fun `an empty body still round-trips`() {
        assertNotNull(PacketEnvelope.unwrap(PacketEnvelope.wrap(ByteArray(0))))
    }

    /**
     * The failure the envelope exists for. Every single-byte corruption must be
     * caught — a body that survives its checksum is parsed as a real report.
     */
    @Test
    fun `every single-byte corruption of the body is detected`() {
        val wrapped = PacketEnvelope.wrap(body)

        for (i in PacketEnvelope.CRC_BYTES until wrapped.size) {
            val damaged = wrapped.copyOf()
            damaged[i] = (damaged[i] + 1).toByte()

            assertNull(PacketEnvelope.unwrap(damaged), "corruption at byte $i went undetected")
        }
    }

    @Test
    fun `a damaged checksum is rejected rather than trusted`() {
        val wrapped = PacketEnvelope.wrap(body)
        wrapped[0] = (wrapped[0] + 1).toByte()

        assertNull(PacketEnvelope.unwrap(wrapped))
    }

    @Test
    fun `a truncated envelope is rejected`() {
        val wrapped = PacketEnvelope.wrap(body)

        assertNull(PacketEnvelope.unwrap(wrapped.copyOfRange(0, 3)))
        assertNull(PacketEnvelope.unwrap(wrapped.copyOfRange(0, wrapped.size - 1)))
    }

    /** Reordered fragments produce a body of the right length and wrong content. */
    @Test
    fun `a body reassembled in the wrong order is caught`() {
        val wrapped = PacketEnvelope.wrap(body)
        val scrambled = wrapped.copyOf()
        val a = scrambled[PacketEnvelope.CRC_BYTES]
        scrambled[PacketEnvelope.CRC_BYTES] = scrambled[scrambled.size - 1]
        scrambled[scrambled.size - 1] = a

        assertNull(PacketEnvelope.unwrap(scrambled))
    }
}

class PacketReceiverTest {

    private val now = 1_787_802_731_000L

    private object NoTransport : MeshTransport {
        override fun discoverPeers(): List<PeerHandle> = emptyList()
        override fun requestDigest(peer: PeerHandle) = null
        override fun send(peer: PeerHandle, packet: MeshPacket) = true
    }

    private fun node(deviceId: String = "receiver") = MeshNode(
        deviceId = DeviceId(deviceId),
        store = InMemoryPacketStore(),
        seen = InMemorySeenSet(),
        transport = NoTransport,
        clock = Clock { now },
    )

    private fun packet(
        n: Int = 1,
        ttl: Int = 10,
        description: String? = "water rising fast on the ground floor",
    ) = MeshPacket(
        packetId = PacketId("00000000-0000-4000-8000-%012d".format(n)),
        emergencyId = EmergencyId("11111111-0000-4000-8000-%012d".format(n)),
        originDeviceId = DeviceId("origin-phone"),
        createdAtDeviceMs = now,
        ttlRemaining = ttl,
        ttlInitial = 10,
        payload = EmergencyPayload(
            typeCode = "FLOOD",
            description = description,
            affectedCount = 4,
        ),
    )

    /** Exactly what a sender puts on the wire, so both halves are tested together. */
    private fun framesFor(p: MeshPacket, mtu: Int = 64): List<ByteArray> =
        ChunkFraming.encode(PacketEnvelope.wrap(PacketCodec.encode(p)), mtu)

    private fun deliver(
        receiver: PacketReceiver,
        frames: List<ByteArray>,
        peer: String = "AA:BB:CC",
    ): InboundResult {
        var last: InboundResult = InboundResult.Buffering
        frames.forEach { last = receiver.onFrameWritten(peer, it, now) }
        return last
    }

    // --- the happy path ---------------------------------------------------

    /**
     * The claim the whole project rests on: a report written on one phone is
     * reconstructed, byte for byte, on another — over a wire that only carries
     * 64-byte writes.
     */
    @Test
    fun `a report survives the trip from one phone to another`() {
        val target = node()
        val receiver = PacketReceiver(target)
        val sent = packet()

        val result = deliver(receiver, framesFor(sent))

        assertTrue(result is InboundResult.Acknowledge)
        assertEquals(AckCode.ACCEPTED, result.code)

        val stored = result.packet
        assertNotNull(stored)
        assertEquals(sent.packetId, stored.packetId)
        assertEquals(sent.emergencyId, stored.emergencyId)
        assertEquals(sent.payload.description, stored.payload.description)
        assertEquals(sent.payload.affectedCount, stored.payload.affectedCount)
    }

    @Test
    fun `the relay stamps its own hop and decrements the ttl`() {
        val receiver = PacketReceiver(node())

        val result = deliver(receiver, framesFor(packet(ttl = 10))) as InboundResult.Acknowledge

        assertEquals(1, result.packet!!.hopCount)
        assertEquals(9, result.packet!!.ttlRemaining)
    }

    /** The identifier that makes loop suppression possible must survive untouched. */
    @Test
    fun `the packet id is not rewritten in transit`() {
        val sent = packet()
        val result = deliver(PacketReceiver(node()), framesFor(sent)) as InboundResult.Acknowledge

        assertEquals(sent.packetId, result.packet!!.packetId)
    }

    @Test
    fun `nothing is acknowledged until the last fragment arrives`() {
        val receiver = PacketReceiver(node())
        val frames = framesFor(packet(), mtu = 40)

        assertTrue(frames.size > 2, "test needs a multi-fragment body")

        frames.dropLast(1).forEach {
            assertEquals(
                InboundResult.Buffering, receiver.onFrameWritten("peer", it, now),
                "acknowledged a report before it was whole",
            )
        }

        assertTrue(receiver.onFrameWritten("peer", frames.last(), now) is InboundResult.Acknowledge)
    }

    @Test
    fun `fragments arriving out of order still reassemble`() {
        val receiver = PacketReceiver(node())
        val frames = framesFor(packet(), mtu = 40).reversed()

        val result = deliver(receiver, frames)

        assertEquals(AckCode.ACCEPTED, (result as InboundResult.Acknowledge).code)
    }

    // --- the refusals -----------------------------------------------------

    @Test
    fun `a corrupted body is refused as CORRUPT rather than parsed`() {
        val frames = framesFor(packet(), mtu = 512).toMutableList()
        val damaged = frames[0].copyOf()
        damaged[damaged.size - 3] = (damaged[damaged.size - 3] + 7).toByte()
        frames[0] = damaged

        val result = deliver(PacketReceiver(node()), frames) as InboundResult.Acknowledge

        assertEquals(AckCode.CORRUPT, result.code)
        assertNull(result.packet, "a corrupt body must not produce a stored report")
    }

    @Test
    fun `a frame from an unsupported protocol version is refused`() {
        val frame = framesFor(packet(), mtu = 512).first().copyOf()
        frame[0] = 0x09

        val result = PacketReceiver(node()).onFrameWritten("peer", frame, now)

        assertEquals(AckCode.UNSUPPORTED, (result as InboundResult.Acknowledge).code)
    }

    /**
     * The same report arriving twice — by two routes, or looping back. The
     * second must cost a lookup and change nothing.
     */
    @Test
    fun `the same report arriving twice is stored once`() {
        val target = node()
        val receiver = PacketReceiver(target)
        val sent = packet()

        val first = deliver(receiver, framesFor(sent)) as InboundResult.Acknowledge
        val second = deliver(receiver, framesFor(sent)) as InboundResult.Acknowledge

        assertEquals(AckCode.ACCEPTED, first.code)
        assertEquals(AckCode.DUPLICATE, second.code)
        assertEquals(1, target.heldPackets().size, "the same report was stored twice")
    }

    /** DUPLICATE is a success for the sender: the peer holds it, which was the goal. */
    @Test
    fun `a duplicate acknowledgement counts as delivered`() {
        assertTrue(AckCode.DUPLICATE.isDelivered)
    }

    @Test
    fun `a report arriving at ttl zero is stored but marked terminal`() {
        val target = node()
        val result = deliver(PacketReceiver(target), framesFor(packet(ttl = 0)))

        assertEquals(AckCode.ACCEPTED_TERMINAL, (result as InboundResult.Acknowledge).code)
        assertEquals(
            1, target.heldPackets().size,
            "a TTL-expired report must still be stored — it may yet reach the server from here",
        )
    }

    @Test
    fun `a full device refuses with NO_CAPACITY rather than dropping silently`() {
        val target = node()
        val receiver = PacketReceiver(target, hasCapacity = { false })

        val result = deliver(receiver, framesFor(packet())) as InboundResult.Acknowledge

        assertEquals(AckCode.NO_CAPACITY, result.code)
        assertEquals(0, target.heldPackets().size)
        assertTrue(!result.code.isPermanent, "a full device should be retried later, not written off")
    }

    // --- signature policy -------------------------------------------------

    /**
     * The protocol point worth defending: a relay does not hold other devices'
     * keys, so it cannot adjudicate a signature. Refusing everything it cannot
     * personally verify would leave it able to carry only its own reports.
     */
    @Test
    fun `a relay carries a packet whose signature it cannot check`() {
        val target = node()
        val receiver = PacketReceiver(target, verify = { Verification.UNKNOWN_KEY })

        val result = deliver(receiver, framesFor(packet())) as InboundResult.Acknowledge

        assertEquals(AckCode.ACCEPTED, result.code)
        assertEquals(1, target.heldPackets().size)
    }

    @Test
    fun `a signature that is checked and wrong is refused permanently`() {
        val target = node()
        val receiver = PacketReceiver(target, verify = { Verification.INVALID })

        val result = deliver(receiver, framesFor(packet())) as InboundResult.Acknowledge

        assertEquals(AckCode.INVALID_HMAC, result.code)
        assertEquals(0, target.heldPackets().size)
        assertTrue(result.code.isPermanent, "resending a bad signature would produce the same answer")
    }

    @Test
    fun `a verified signature is accepted`() {
        val receiver = PacketReceiver(node(), verify = { Verification.VALID })

        val result = deliver(receiver, framesFor(packet())) as InboundResult.Acknowledge

        assertEquals(AckCode.ACCEPTED, result.code)
    }

    // --- buffers ----------------------------------------------------------

    /**
     * Two peers mid-transfer at once is ordinary in a crowd. Their fragments
     * must not be assembled into each other's reports.
     */
    @Test
    fun `interleaved transfers from two peers do not contaminate each other`() {
        val target = node()
        val receiver = PacketReceiver(target)

        val one = framesFor(packet(1), mtu = 40)
        val two = framesFor(packet(2), mtu = 40)

        // Strictly alternating, which is the worst case for a shared buffer.
        val longest = maxOf(one.size, two.size)
        for (i in 0 until longest) {
            one.getOrNull(i)?.let { receiver.onFrameWritten("peer-one", it, now) }
            two.getOrNull(i)?.let { receiver.onFrameWritten("peer-two", it, now) }
        }

        assertEquals(2, target.heldPackets().size)
        assertEquals(
            setOf(packet(1).packetId, packet(2).packetId),
            target.heldPackets().map { it.packetId }.toSet(),
        )
    }

    /**
     * A peer walking out of range mid-transfer is an ordinary BLE event, not an
     * error — but without expiry a phone in a crowd accumulates half-messages
     * until it runs out of memory.
     */
    @Test
    fun `a transfer abandoned mid-way does not hold a buffer forever`() {
        val receiver = PacketReceiver(node())
        val frames = framesFor(packet(), mtu = 40)

        receiver.onFrameWritten("departing-peer", frames.first(), now)
        assertEquals(1, receiver.openBufferCount())

        receiver.onPeerDisconnected(now + 11_000)
        assertEquals(0, receiver.openBufferCount(), "an abandoned transfer leaked its buffer")
    }

    @Test
    fun `a resumed transfer after expiry does not half-assemble`() {
        val target = node()
        val receiver = PacketReceiver(target)
        val frames = framesFor(packet(), mtu = 40)

        receiver.onFrameWritten("peer", frames.first(), now)
        receiver.onPeerDisconnected(now + 11_000)

        // The rest arrives after the buffer is gone: nothing may be stored.
        frames.drop(1).forEach { receiver.onFrameWritten("peer", it, now + 12_000) }

        assertEquals(0, target.heldPackets().size)
    }

    // --- the two halves together ------------------------------------------

    /**
     * The end-to-end check: what [BleSession] decides to send is exactly what
     * [PacketReceiver] can accept. These two classes are the only implementations
     * of each side, and nothing else verifies that they agree on the wire.
     */
    @Test
    fun `what a session offers is what a receiver accepts`() {
        val held = listOf(packet(1), packet(2))
        val session = BleSession(heldPackets = held, isForwardable = { _, _ -> true })
        val target = node()
        val receiver = PacketReceiver(target)

        session.start()
        session.onEvent(BleEvent.MtuNegotiated(64))
        session.onEvent(BleEvent.NodeInfoRead(DeviceId("receiver"), GattContract.PROTOCOL_VERSION))

        var action = session.onEvent(BleEvent.DigestRead(null))

        while (action is BleAction.SendPacket) {
            val result = deliver(receiver, action.frames)

            assertTrue(result is InboundResult.Acknowledge, "receiver never completed a message")
            assertEquals(
                AckCode.ACCEPTED, result.code,
                "receiver refused a packet the session framed",
            )

            action = session.onEvent(BleEvent.PacketAcked(action.packetId, result.code))
        }

        assertEquals(2, target.heldPackets().size, "not every offered report arrived")
        assertEquals(2, session.summary().deliveredCount)
    }
}
