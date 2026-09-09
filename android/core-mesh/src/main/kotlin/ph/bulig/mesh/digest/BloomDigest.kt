package ph.bulig.mesh.digest

import ph.bulig.mesh.model.PacketId

/**
 * A compact summary of the packet ids a device holds.
 *
 * Exchanged BEFORE any payload, so a packet is never transmitted to a peer that
 * already has it. This is classic epidemic-routing anti-entropy, and it is what
 * stops a dense cluster of phones saturating the air with redundant copies.
 *
 * A false positive causes a packet to be SKIPPED, never duplicated — the safe
 * failure direction. The next session, with a different filter state, catches
 * what this one missed.
 *
 * 256 bytes with 3 hash functions gives roughly a 1% false-positive rate at 200
 * held packets, and fits comfortably in a handful of BLE reads.
 *
 * @see docs/06-ble-protocol.md 6.3
 */
class BloomDigest private constructor(
    private val bits: ByteArray,
    private val hashCount: Int,
) {

    val sizeBytes: Int get() = bits.size

    fun mightContain(id: PacketId): Boolean =
        indicesFor(id.value).all { (byte, mask) -> bits[byte].toInt() and mask != 0 }

    fun toByteArray(): ByteArray = bits.copyOf()

    private fun add(id: PacketId) {
        indicesFor(id.value).forEach { (byte, mask) ->
            bits[byte] = (bits[byte].toInt() or mask).toByte()
        }
    }

    /**
     * Derives [hashCount] independent positions from two base hashes, rather
     * than running k separate digests — standard Kirsch–Mitzenmacher, and cheap
     * enough to run on every packet during a scan window.
     */
    private fun indicesFor(value: String): List<Pair<Int, Int>> {
        val h1 = fnv1a32(value)
        val h2 = fnv1a32(value.reversed()) or 1 // odd, so it strides the space

        return (0 until hashCount).map { i ->
            val bitIndex = ((h1 + i * h2).toLong() and 0xFFFFFFFFL).toInt() % (bits.size * 8)
            val positive = if (bitIndex < 0) bitIndex + bits.size * 8 else bitIndex
            positive / 8 to (1 shl (positive % 8))
        }
    }

    private fun fnv1a32(value: String): Int {
        var hash = -0x7EE3623B // 2166136261
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toInt() and 0xFF)
            hash *= 16777619
        }
        return hash
    }

    companion object {
        const val DEFAULT_SIZE_BYTES: Int = 256
        const val DEFAULT_HASH_COUNT: Int = 3

        fun of(
            ids: Collection<PacketId>,
            sizeBytes: Int = DEFAULT_SIZE_BYTES,
            hashCount: Int = DEFAULT_HASH_COUNT,
        ): BloomDigest = BloomDigest(ByteArray(sizeBytes), hashCount).apply {
            ids.forEach { add(it) }
        }

        fun fromByteArray(
            bytes: ByteArray,
            hashCount: Int = DEFAULT_HASH_COUNT,
        ): BloomDigest = BloomDigest(bytes.copyOf(), hashCount)

        /** An empty digest: a peer that holds nothing, or would not answer. */
        fun empty(sizeBytes: Int = DEFAULT_SIZE_BYTES): BloomDigest =
            BloomDigest(ByteArray(sizeBytes), DEFAULT_HASH_COUNT)
    }
}
