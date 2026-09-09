package ph.bulig.mesh.framing

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A negotiated ATT MTU is typically 185–517 bytes, and an emergency carrying a
 * description exceeds that. Assuming one BLE write equals one message is where
 * most student BLE projects quietly fail, so the framing is tested against the
 * ways a real transfer actually goes wrong: fragments arriving out of order,
 * fragments lost, and peers walking out of range mid-transfer.
 *
 * @see docs/06-ble-protocol.md 6.4
 */
class ChunkFramingTest {

    private val realisticBody = ByteArray(842) { (it % 251).toByte() }

    @Test
    fun `a body larger than the mtu is split across several frames`() {
        val frames = ChunkFraming.encode(realisticBody, mtu = 247)

        assertTrue(frames.size > 1, "an 842-byte report cannot fit in one 247-byte write")
        frames.forEach { assertTrue(it.size <= 247, "frame of ${it.size} bytes exceeds the MTU") }

        assertEquals(ChunkFraming.FLAG_FIRST, frames.first()[1].toInt() and ChunkFraming.FLAG_FIRST)
        assertEquals(ChunkFraming.FLAG_LAST, frames.last()[1].toInt() and ChunkFraming.FLAG_LAST)
    }

    @Test
    fun `a reassembled body is byte-identical to the original`() {
        val frames = ChunkFraming.encode(realisticBody, mtu = 185)
        val reassembler = ChunkReassembler()

        var result: ReassemblyResult = ReassemblyResult.Incomplete
        frames.forEach { result = reassembler.accept("peer-1", it, nowMs = 0) }

        assertTrue(result is ReassemblyResult.Complete)
        assertTrue(realisticBody.contentEquals((result as ReassemblyResult.Complete).body))
    }

    /** BLE does not guarantee ordering, so arrival order must not matter. */
    @Test
    fun `fragments arriving out of order still reassemble correctly`() {
        val frames = ChunkFraming.encode(realisticBody, mtu = 185).shuffled(Random(3))
        val reassembler = ChunkReassembler()

        var result: ReassemblyResult = ReassemblyResult.Incomplete
        frames.forEach { result = reassembler.accept("peer-1", it, nowMs = 0) }

        assertTrue(result is ReassemblyResult.Complete)
        assertTrue(realisticBody.contentEquals((result as ReassemblyResult.Complete).body))
    }

    @Test
    fun `a message with a missing fragment never completes`() {
        val frames = ChunkFraming.encode(realisticBody, mtu = 185).toMutableList()
        frames.removeAt(1)

        val reassembler = ChunkReassembler()
        frames.forEach {
            assertEquals(
                ReassemblyResult.Incomplete,
                reassembler.accept("peer-1", it, nowMs = 0),
                "a gap must never be assembled into a corrupt body",
            )
        }
    }

    /**
     * A peer walking out of range mid-transfer is an ordinary BLE event, not an
     * error. Without expiry, a phone left running in a crowd accumulates
     * half-messages until it runs out of memory.
     */
    @Test
    fun `an abandoned transfer expires instead of leaking`() {
        val reassembler = ChunkReassembler(expiryMs = 10_000)
        val frames = ChunkFraming.encode(realisticBody, mtu = 185)

        reassembler.accept("peer-1", frames.first(), nowMs = 0)
        assertEquals(1, reassembler.openBufferCount())

        reassembler.expire(nowMs = 11_000)
        assertEquals(0, reassembler.openBufferCount(), "the stale buffer should be released")
    }

    /** Several peers may be mid-transfer at once; their fragments must not mix. */
    @Test
    fun `concurrent transfers from different peers do not interfere`() {
        val bodyA = ByteArray(400) { 1 }
        val bodyB = ByteArray(400) { 2 }
        val framesA = ChunkFraming.encode(bodyA, mtu = 185)
        val framesB = ChunkFraming.encode(bodyB, mtu = 185)
        val reassembler = ChunkReassembler()

        // Interleave the two transfers, as two peers in range would produce.
        var resultA: ReassemblyResult = ReassemblyResult.Incomplete
        var resultB: ReassemblyResult = ReassemblyResult.Incomplete
        for (i in framesA.indices) {
            resultA = reassembler.accept("peer-A", framesA[i], nowMs = 0)
            resultB = reassembler.accept("peer-B", framesB[i], nowMs = 0)
        }

        assertTrue(resultA is ReassemblyResult.Complete)
        assertTrue(resultB is ReassemblyResult.Complete)
        assertTrue(bodyA.contentEquals((resultA as ReassemblyResult.Complete).body))
        assertTrue(bodyB.contentEquals((resultB as ReassemblyResult.Complete).body))
    }

    @Test
    fun `a frame from an unsupported protocol version is rejected`() {
        val frame = ChunkFraming.encode(realisticBody, mtu = 185).first().copyOf()
        frame[0] = 0x09

        val result = ChunkReassembler().accept("peer-1", frame, nowMs = 0)

        assertTrue(result is ReassemblyResult.Rejected)
        assertTrue((result as ReassemblyResult.Rejected).reason.contains("version"))
    }

    @Test
    fun `a truncated frame is rejected rather than parsed`() {
        val result = ChunkReassembler().accept("peer-1", byteArrayOf(0x01, 0x03), nowMs = 0)

        assertTrue(result is ReassemblyResult.Rejected)
    }

    @Test
    fun `crc detects a corrupted body`() {
        val corrupted = realisticBody.copyOf().also { it[500] = (it[500] + 1).toByte() }

        assertNotEquals(ChunkFraming.crc32(realisticBody), ChunkFraming.crc32(corrupted))
    }

    @Test
    fun `an empty body still produces one well formed frame`() {
        val frames = ChunkFraming.encode(ByteArray(0), mtu = 185)

        assertEquals(1, frames.size)
        val result = ChunkReassembler().accept("peer-1", frames.first(), nowMs = 0)
        assertTrue(result is ReassemblyResult.Complete)
        assertEquals(0, (result as ReassemblyResult.Complete).body.size)
    }
}
