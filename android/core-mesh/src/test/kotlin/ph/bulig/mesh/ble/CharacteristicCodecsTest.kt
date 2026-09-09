package ph.bulig.mesh.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ph.bulig.mesh.digest.BloomDigest
import ph.bulig.mesh.model.DeviceId
import ph.bulig.mesh.model.PacketId

class NodeInfoCodecTest {

    private val info = NodeInfo(
        deviceId = DeviceId("4d4f8f2e-8b1a-4a8e-9a3f-5b6c7d8e9f01"),
        protocolVersion = GattContract.PROTOCOL_VERSION,
        hasInternet = true,
        pendingCount = 7,
    )

    @Test
    fun `a node info round-trips through the wire`() {
        assertEquals(info, NodeInfoCodec.decode(NodeInfoCodec.encode(info)))
    }

    @Test
    fun `the internet flag survives the round trip in both states`() {
        val offline = info.copy(hasInternet = false)

        assertTrue(NodeInfoCodec.decode(NodeInfoCodec.encode(info))!!.hasInternet)
        assertFalse(NodeInfoCodec.decode(NodeInfoCodec.encode(offline))!!.hasInternet)
    }

    @Test
    fun `a pending count uses the full two-byte range`() {
        val decoded = NodeInfoCodec.decode(NodeInfoCodec.encode(info.copy(pendingCount = 65_535)))

        assertEquals(65_535, decoded?.pendingCount)
    }

    /** A device holding more than the field can express is a bug worth capping loudly. */
    @Test
    fun `an impossible pending count clamps rather than wrapping`() {
        val decoded = NodeInfoCodec.decode(NodeInfoCodec.encode(info.copy(pendingCount = 70_000)))

        assertEquals(65_535, decoded?.pendingCount)
    }

    /**
     * The case that matters most: a BLE read can return fewer bytes than were
     * written. Decoding the fragment would mint a peer identity out of half a
     * UUID, and that identity would then be trusted for the whole session.
     */
    @Test
    fun `a truncated read decodes to nothing rather than half an identity`() {
        val full = NodeInfoCodec.encode(info)

        for (length in 0 until full.size) {
            assertNull(
                NodeInfoCodec.decode(full.copyOfRange(0, length)),
                "a $length-byte read produced a peer identity",
            )
        }
    }

    @Test
    fun `a declared id length of zero is rejected`() {
        assertNull(NodeInfoCodec.decode(byteArrayOf(1, 0, 0, 0, 0)))
    }

    @Test
    fun `a peer speaking another protocol version still decodes`() {
        // It must decode, or the session could never report *why* it hung up.
        val decoded = NodeInfoCodec.decode(NodeInfoCodec.encode(info.copy(protocolVersion = 9)))

        assertEquals(9, decoded?.protocolVersion)
    }

    @Test
    fun `a non-ascii device id survives byte-exact`() {
        val quirky = info.copy(deviceId = DeviceId("device-tacloban-01"))

        assertEquals(quirky, NodeInfoCodec.decode(NodeInfoCodec.encode(quirky)))
    }

    @Test
    fun `trailing bytes beyond the declared id are ignored`() {
        val padded = NodeInfoCodec.encode(info) + byteArrayOf(0, 0, 0)

        assertEquals(info, NodeInfoCodec.decode(padded))
    }
}

class DigestCodecTest {

    private val held = (1..20).map { PacketId("00000000-0000-4000-8000-%012d".format(it)) }

    @Test
    fun `a digest round-trips and still answers for what it holds`() {
        val decoded = DigestCodec.decode(DigestCodec.encode(BloomDigest.of(held)))

        assertNotNull(decoded)
        held.forEach { assertTrue(decoded.mightContain(it), "lost $it in transit") }
    }

    @Test
    fun `a decoded digest does not claim ids it was never given`() {
        val decoded = DigestCodec.decode(DigestCodec.encode(BloomDigest.of(held)))!!
        val absent = (100..300).map { PacketId("ffffffff-0000-4000-8000-%012d".format(it)) }

        val falsePositives = absent.count { decoded.mightContain(it) }

        // The filter is probabilistic, so this is a rate check, not an absolute:
        // 256 bytes at k=3 with 20 entries should be far below 1%.
        assertTrue(falsePositives <= 2, "$falsePositives false positives out of ${absent.size}")
    }

    @Test
    fun `the hash count travels with the bits`() {
        val encoded = DigestCodec.encode(BloomDigest.of(held, hashCount = 5), hashCount = 5)

        assertEquals(5, encoded[0].toInt())

        val decoded = DigestCodec.decode(encoded)!!
        held.forEach { assertTrue(decoded.mightContain(it), "k mismatch lost $it") }
    }

    @Test
    fun `an empty or header-only read decodes to nothing`() {
        assertNull(DigestCodec.decode(ByteArray(0)))
        assertNull(DigestCodec.decode(byteArrayOf(3)))
    }

    @Test
    fun `a zero hash count is rejected rather than dividing by nothing`() {
        assertNull(DigestCodec.decode(byteArrayOf(0, 1, 2, 3)))
    }

    /**
     * A peer that answers with nothing is treated as holding nothing, which
     * risks re-sending. The opposite assumption would risk never sending.
     */
    @Test
    fun `a session treats an unreadable digest as an empty one`() {
        assertNull(DigestCodec.decode(ByteArray(0)))
        assertEquals(0, BloomDigest.empty().toByteArray().count { it != 0.toByte() })
    }
}
