package ph.bulig.mesh.framing

import java.util.zip.CRC32
import ph.bulig.mesh.model.PacketId

/**
 * Splits a packet across BLE writes, and puts it back together.
 *
 * A negotiated ATT MTU is typically 185–517 bytes, and an emergency carrying a
 * description exceeds that. This is where most student BLE projects quietly
 * fail — they assume one write is one message.
 *
 * Frame layout (docs/06-ble-protocol.md 6.4):
 *
 *   +--------+--------+--------+--------+---------------------+
 *   | VER    | FLAGS  | SEQ    | TOTAL  |  PAYLOAD FRAGMENT   |
 *   +--------+--------+--------+--------+---------------------+
 *     1 byte   1 byte   1 byte   1 byte    up to (MTU - 4)
 */
object ChunkFraming {
    const val VERSION: Byte = 0x01
    const val HEADER_BYTES: Int = 4
    const val MAX_FRAGMENTS: Int = 255

    const val FLAG_FIRST: Int = 0x01
    const val FLAG_LAST: Int = 0x02

    fun encode(body: ByteArray, mtu: Int): List<ByteArray> {
        val capacity = mtu - HEADER_BYTES
        require(capacity > 0) { "MTU $mtu is too small to carry a frame header" }

        val total = if (body.isEmpty()) 1 else (body.size + capacity - 1) / capacity
        require(total <= MAX_FRAGMENTS) {
            "Body of ${body.size} bytes needs $total fragments, over the $MAX_FRAGMENTS limit"
        }

        return (0 until total).map { seq ->
            val start = seq * capacity
            val end = minOf(start + capacity, body.size)
            val fragment = if (start >= body.size) ByteArray(0) else body.copyOfRange(start, end)

            var flags = 0
            if (seq == 0) flags = flags or FLAG_FIRST
            if (seq == total - 1) flags = flags or FLAG_LAST

            ByteArray(HEADER_BYTES + fragment.size).also { frame ->
                frame[0] = VERSION
                frame[1] = flags.toByte()
                frame[2] = seq.toByte()
                frame[3] = total.toByte()
                fragment.copyInto(frame, HEADER_BYTES)
            }
        }
    }

    fun crc32(body: ByteArray): Long = CRC32().apply { update(body) }.value
}

sealed interface ReassemblyResult {
    /** More fragments are still expected. */
    data object Incomplete : ReassemblyResult

    data class Complete(val body: ByteArray) : ReassemblyResult {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Complete && body.contentEquals(other.body))

        override fun hashCode(): Int = body.contentHashCode()
    }

    data class Rejected(val reason: String) : ReassemblyResult
}

/**
 * Buffers fragments per (peer, packet) until a message is whole.
 *
 * Buffers expire, because a peer can walk out of range mid-transfer — over BLE
 * that is an ordinary event, not an error. Without expiry, a phone left running
 * in a crowd would accumulate half-messages until it ran out of memory.
 */
class ChunkReassembler(
    private val expiryMs: Long = DEFAULT_EXPIRY_MS,
) {

    private data class Buffer(
        val total: Int,
        val fragments: MutableMap<Int, ByteArray> = mutableMapOf(),
        var lastActivityMs: Long,
    )

    private val buffers = HashMap<Key, Buffer>()

    private data class Key(val peer: String, val packetId: PacketId?)

    fun accept(peer: String, frame: ByteArray, nowMs: Long, packetId: PacketId? = null): ReassemblyResult {
        if (frame.size < ChunkFraming.HEADER_BYTES) {
            return ReassemblyResult.Rejected("frame shorter than its header")
        }
        if (frame[0] != ChunkFraming.VERSION) {
            return ReassemblyResult.Rejected("unsupported protocol version ${frame[0]}")
        }

        val seq = frame[2].toInt() and 0xFF
        val total = frame[3].toInt() and 0xFF
        if (total == 0) return ReassemblyResult.Rejected("fragment count of zero")
        if (seq >= total) return ReassemblyResult.Rejected("fragment $seq outside a $total-fragment message")

        expire(nowMs)

        val key = Key(peer, packetId)
        val buffer = buffers.getOrPut(key) { Buffer(total, lastActivityMs = nowMs) }

        if (buffer.total != total) {
            // The peer restarted the transfer with a different message.
            buffers[key] = Buffer(total, lastActivityMs = nowMs)
        }

        val current = buffers.getValue(key)
        current.fragments[seq] = frame.copyOfRange(ChunkFraming.HEADER_BYTES, frame.size)
        current.lastActivityMs = nowMs

        if (current.fragments.size < total) return ReassemblyResult.Incomplete

        // Fragments may arrive out of order; assemble by sequence, not arrival.
        val body = ByteArray(current.fragments.values.sumOf { it.size })
        var offset = 0
        for (i in 0 until total) {
            val fragment = current.fragments.getValue(i)
            fragment.copyInto(body, offset)
            offset += fragment.size
        }

        buffers.remove(key)
        return ReassemblyResult.Complete(body)
    }

    fun expire(nowMs: Long) {
        buffers.entries.removeIf { nowMs - it.value.lastActivityMs > expiryMs }
    }

    fun openBufferCount(): Int = buffers.size

    companion object {
        const val DEFAULT_EXPIRY_MS: Long = 10_000
    }
}
