package ph.bulig.mesh.ble

import ph.bulig.mesh.MeshNode
import ph.bulig.mesh.framing.ChunkFraming
import ph.bulig.mesh.framing.ChunkReassembler
import ph.bulig.mesh.framing.ReassemblyResult
import ph.bulig.mesh.model.MeshPacket

/**
 * The integrity envelope wrapped around an encoded packet body.
 *
 * ```
 * [crc32:4 big-endian][body]
 * ```
 *
 * BLE's link layer already CRCs each individual radio packet, so this is not
 * about radio noise. It catches the failure one layer up: fragments reassembled
 * in the wrong order, a buffer collision between two peers, a truncated final
 * write — corruption produced by *our own* framing, which the radio's own check
 * cannot see because each fragment arrived intact.
 *
 * The receive algorithm in `docs/06-ble-protocol.md` §6.5 has always specified
 * `ACK(CORRUPT)` on a CRC mismatch. Until this existed, `ChunkFraming.crc32`
 * was computed nowhere and transmitted nowhere, so that branch could never fire
 * and a scrambled body would have been parsed as if it were real. A garbled
 * report is worse than a dropped one: it looks legitimate on an operator's
 * screen.
 */
object PacketEnvelope {

    const val CRC_BYTES = 4

    fun wrap(body: ByteArray): ByteArray {
        val crc = ChunkFraming.crc32(body)

        return ByteArray(CRC_BYTES + body.size).also { out ->
            out[0] = ((crc shr 24) and 0xFF).toByte()
            out[1] = ((crc shr 16) and 0xFF).toByte()
            out[2] = ((crc shr 8) and 0xFF).toByte()
            out[3] = (crc and 0xFF).toByte()
            body.copyInto(out, CRC_BYTES)
        }
    }

    /** Returns null when the envelope is too short or the checksum disagrees. */
    fun unwrap(framed: ByteArray): ByteArray? {
        if (framed.size < CRC_BYTES) return null

        val declared = ((framed[0].toLong() and 0xFF) shl 24) or
            ((framed[1].toLong() and 0xFF) shl 16) or
            ((framed[2].toLong() and 0xFF) shl 8) or
            (framed[3].toLong() and 0xFF)

        val body = framed.copyOfRange(CRC_BYTES, framed.size)

        return if (ChunkFraming.crc32(body) == declared) body else null
    }
}

/** What the GATT server should do after one inbound write. */
sealed interface InboundResult {
    /** More fragments expected. Nothing to acknowledge yet. */
    data object Buffering : InboundResult

    /** A message completed. Notify [code] on the ACK characteristic. */
    data class Acknowledge(val code: AckCode, val packet: MeshPacket?) : InboundResult
}

/**
 * What a relay can conclude about a packet's origin signature.
 *
 * The three-way answer is the whole point, and it is a protocol fact rather
 * than an implementation convenience: **a relay does not hold other devices'
 * keys.** Only the server, which provisioned them, can adjudicate a signature.
 * `PacketSigner.verifyOwn` is named as narrowly as it is for this reason.
 *
 * So a relay's honest answer about a stranger's packet is [UNKNOWN_KEY] — "I
 * cannot tell" — and the packet is carried anyway, because refusing to relay
 * everything it cannot personally verify would leave the mesh able to carry
 * only its own reports, which is not a mesh.
 */
enum class Verification {
    VALID,

    /** Checked against a key this device holds, and wrong. Do not store. */
    INVALID,

    /** No key for this origin. Carry it; the server decides. */
    UNKNOWN_KEY,
}

/**
 * The receiving half of the protocol — the counterpart to [BleSession].
 *
 * [BleSession] decides what this device *offers*. This decides what it
 * *accepts*, and it is the half the whole store-and-forward claim rests on: a
 * mesh where every node can send but none can receive carries nothing.
 *
 * Implements `docs/06-ble-protocol.md` §6.5 in the order the document
 * specifies, and the order is not arbitrary. Structural checks run before
 * cryptographic ones, and the duplicate check runs before verification so a
 * packet looping around a dense crowd costs a map lookup rather than an HMAC
 * over its whole body — on a phone whose battery is the scarcest thing it has.
 *
 * Lives in `:core-mesh` with no Android types, so every branch below is
 * exercised by tests rather than first discovered on a handset in a flood.
 */
class PacketReceiver(
    private val node: MeshNode,
    private val reassembler: ChunkReassembler = ChunkReassembler(),
    /**
     * How this device judges an origin signature. The default is the honest
     * one for a relay: it cannot tell, so it carries the packet regardless.
     */
    private val verify: (MeshPacket) -> Verification = { Verification.UNKNOWN_KEY },
    /** False when local storage is full. Checked last: it is the only recoverable refusal. */
    private val hasCapacity: () -> Boolean = { true },
) {

    /**
     * One frame written to `PACKET_IN` by [peer].
     *
     * [peer] is the transport address, not a device identity — the sender's own
     * id is inside the packet and is not trusted to key buffers, because two
     * peers could claim the same one.
     */
    fun onFrameWritten(peer: String, frame: ByteArray, nowMs: Long): InboundResult =
        when (val assembled = reassembler.accept(peer, frame, nowMs)) {
            is ReassemblyResult.Incomplete -> InboundResult.Buffering

            // A version this device cannot frame for. Retrying cannot help, so
            // the sender is told to stop rather than to back off.
            is ReassemblyResult.Rejected -> InboundResult.Acknowledge(AckCode.UNSUPPORTED, null)

            is ReassemblyResult.Complete -> admit(assembled.body)
        }

    /** Frees buffers for a peer that walked away mid-transfer. */
    fun onPeerDisconnected(nowMs: Long) = reassembler.expire(nowMs)

    fun openBufferCount(): Int = reassembler.openBufferCount()

    private fun admit(framed: ByteArray): InboundResult {
        val body = PacketEnvelope.unwrap(framed)
            ?: return InboundResult.Acknowledge(AckCode.CORRUPT, null)

        // A body that survived its checksum but will not decode is a protocol
        // disagreement, not a damaged transfer — so it is not CORRUPT, and
        // resending it would produce the same result.
        val packet = PacketCodec.decode(body)
            ?: return InboundResult.Acknowledge(AckCode.UNSUPPORTED, null)

        // Before verification: a duplicate is the common case in a dense crowd,
        // and it is answered with a map lookup instead of anything costlier.
        if (node.hasSeen(packet.packetId)) {
            return InboundResult.Acknowledge(AckCode.DUPLICATE, null)
        }

        // Only a signature this device can actually check, and which fails, is
        // grounds for refusal. "I cannot tell" means carry it.
        if (verify(packet) == Verification.INVALID) {
            return InboundResult.Acknowledge(AckCode.INVALID_HMAC, null)
        }

        // Last, because it is the one refusal the sender should retry later
        // rather than give up on.
        if (!hasCapacity()) {
            return InboundResult.Acknowledge(AckCode.NO_CAPACITY, null)
        }

        return when (val outcome = node.onPacketReceived(packet)) {
            is MeshNode.ReceiveOutcome.Accepted ->
                InboundResult.Acknowledge(AckCode.ACCEPTED, outcome.packet)

            // Stored, and still syncable from here — it merely stops travelling.
            is MeshNode.ReceiveOutcome.AcceptedTerminal ->
                InboundResult.Acknowledge(AckCode.ACCEPTED_TERMINAL, outcome.packet)

            // Reachable when the seen-set changed between the check above and
            // here; the node's own guard is authoritative.
            MeshNode.ReceiveOutcome.Duplicate ->
                InboundResult.Acknowledge(AckCode.DUPLICATE, null)
        }
    }
}
