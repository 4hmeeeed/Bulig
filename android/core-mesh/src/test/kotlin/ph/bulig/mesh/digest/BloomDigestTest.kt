package ph.bulig.mesh.digest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ph.bulig.mesh.model.PacketId

/**
 * The digest is exchanged before any payload, so a packet is never transmitted
 * to a peer that already holds it. Its correctness properties are asymmetric:
 * it must NEVER report "absent" for something present, and it MAY occasionally
 * report "present" for something absent.
 *
 * That asymmetry is the whole safety argument — a false positive costs one
 * skipped transfer, which the next encounter retries. A false negative would
 * cost redundant traffic, and a broken "never absent" guarantee would cost
 * correctness.
 *
 * @see docs/06-ble-protocol.md 6.3
 */
class BloomDigestTest {

    private fun ids(n: Int, prefix: String = "p") =
        (1..n).map { PacketId("$prefix-0000-4000-8000-%012d".format(it)) }

    @Test
    fun `every id that was added is reported present`() {
        val held = ids(200)
        val digest = BloomDigest.of(held)

        // The guarantee that must never break: a held packet is never re-sent.
        held.forEach {
            assertTrue(digest.mightContain(it), "$it was added but reported absent")
        }
    }

    @Test
    fun `an empty digest reports nothing present`() {
        val digest = BloomDigest.empty()

        ids(50).forEach { assertFalse(digest.mightContain(it)) }
    }

    /**
     * At the design load of 200 packets in 256 bytes, the false-positive rate
     * should sit near 1%. A skip is the safe failure: the packet simply travels
     * on the next encounter, when the peer's filter state differs.
     */
    @Test
    fun `false positive rate stays low at the designed capacity`() {
        val digest = BloomDigest.of(ids(200, prefix = "held"))
        val absent = ids(2000, prefix = "other")

        val falsePositives = absent.count { digest.mightContain(it) }
        val rate = falsePositives.toDouble() / absent.size

        assertTrue(
            rate < 0.05,
            "false positive rate of ${"%.3f".format(rate)} is too high; " +
                "too many packets would be skipped rather than relayed",
        )
    }

    @Test
    fun `a digest survives the round trip over the wire`() {
        val held = ids(120)
        val original = BloomDigest.of(held)

        val restored = BloomDigest.fromByteArray(original.toByteArray())

        held.forEach { assertTrue(restored.mightContain(it), "$it lost in transit") }
        assertEquals(original.sizeBytes, restored.sizeBytes)
    }

    @Test
    fun `the digest fits comfortably in a handful of ble reads`() {
        assertEquals(256, BloomDigest.of(ids(200)).sizeBytes)
    }

    /** Mutating the returned array must not corrupt the digest it came from. */
    @Test
    fun `the exported byte array is a copy`() {
        val digest = BloomDigest.of(ids(10))
        val exported = digest.toByteArray()

        exported.fill(0)

        assertTrue(
            ids(10).all { digest.mightContain(it) },
            "the digest was corrupted by a caller mutating its exported bytes",
        )
    }
}
