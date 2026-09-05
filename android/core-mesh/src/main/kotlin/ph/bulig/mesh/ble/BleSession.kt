package ph.bulig.mesh.ble

import ph.bulig.mesh.digest.BloomDigest
import ph.bulig.mesh.framing.ChunkFraming
import ph.bulig.mesh.model.DeviceId
import ph.bulig.mesh.model.MeshPacket
import ph.bulig.mesh.model.PacketId

/**
 * What the orchestrator wants the radio to do next.
 *
 * Expressed as data rather than executed directly, so the whole encounter can be
 * driven and asserted in a test with no Bluetooth stack present. The Android
 * layer's job is to carry these out and feed the results back.
 */
sealed interface BleAction {
    data object RequestMtu : BleAction
    data object ReadNodeInfo : BleAction
    data object ReadDigest : BleAction

    /** One packet, already split for the negotiated MTU. */
    data class SendPacket(
        val packetId: PacketId,
        val frames: List<ByteArray>,
    ) : BleAction

    data class Disconnect(val reason: String) : BleAction
}

/** What actually happened, fed back in from the radio. */
sealed interface BleEvent {
    data class MtuNegotiated(val mtu: Int) : BleEvent
    data class NodeInfoRead(val peerId: DeviceId, val protocolVersion: Int) : BleEvent
    data class DigestRead(val digest: BloomDigest?) : BleEvent
    data class PacketAcked(val packetId: PacketId, val code: AckCode) : BleEvent
    data class Failed(val reason: String) : BleEvent
}

/** Why a session ended, and what the caller should conclude from it. */
data class SessionSummary(
    val peerId: DeviceId?,
    val delivered: List<PacketId> = emptyList(),
    val skipped: Int = 0,
    val failures: Map<PacketId, AckCode> = emptyMap(),
    val endedBecause: String = "",
) {
    val deliveredCount: Int get() = delivered.size
}

/**
 * One encounter with one peer, as a state machine.
 *
 * The sequence is fixed by the protocol: negotiate an MTU, learn who the peer
 * is, read what it already holds, then offer only what it is missing. Modelling
 * it explicitly rather than as nested Bluetooth callbacks is what makes it
 * testable — and BLE callback code is notoriously where ordering bugs hide,
 * because every step is asynchronous and any of them can fail.
 *
 * @see docs/06-ble-protocol.md 6.3
 */
class BleSession(
    private val heldPackets: List<MeshPacket>,
    private val isForwardable: (MeshPacket, DeviceId) -> Boolean,
) {

    private enum class Phase { NEGOTIATING, IDENTIFYING, COMPARING, SENDING, DONE }

    private var phase = Phase.NEGOTIATING
    private var mtu = GattContract.MINIMUM_MTU
    private var peerId: DeviceId? = null

    private var queue: MutableList<MeshPacket> = mutableListOf()
    private val delivered = mutableListOf<PacketId>()
    private val failures = mutableMapOf<PacketId, AckCode>()
    private var skipped = 0
    private var inFlight: PacketId? = null
    private var endedBecause = ""

    val isFinished: Boolean get() = phase == Phase.DONE

    fun start(): BleAction = BleAction.RequestMtu

    /**
     * Advances the session.
     *
     * Every path either produces the next action or ends the session — an
     * encounter that stalls silently would hold a GATT connection open against
     * a limit most phones cap at a handful.
     */
    fun onEvent(event: BleEvent): BleAction = when (event) {
        is BleEvent.MtuNegotiated -> {
            // A peer that grants less than requested is normal, not an error.
            mtu = event.mtu.coerceAtLeast(GattContract.MINIMUM_MTU)
            phase = Phase.IDENTIFYING
            BleAction.ReadNodeInfo
        }

        is BleEvent.NodeInfoRead -> {
            peerId = event.peerId
            if (event.protocolVersion != GattContract.PROTOCOL_VERSION) {
                // Talking to a version we cannot frame for would corrupt the
                // peer's buffers rather than merely failing.
                finish("peer speaks protocol v${event.protocolVersion}")
            } else {
                phase = Phase.COMPARING
                BleAction.ReadDigest
            }
        }

        is BleEvent.DigestRead -> {
            // A peer that will not answer is assumed to hold nothing. That risks
            // re-sending, which costs airtime; assuming it holds everything would
            // risk never sending, which costs a delivery.
            buildQueue(event.digest ?: BloomDigest.empty())
            sendNextOrFinish()
        }

        is BleEvent.PacketAcked -> {
            recordAck(event.packetId, event.code)
            sendNextOrFinish()
        }

        is BleEvent.Failed -> finish(event.reason)
    }

    fun summary(): SessionSummary = SessionSummary(
        peerId = peerId,
        delivered = delivered.toList(),
        skipped = skipped,
        failures = failures.toMap(),
        endedBecause = endedBecause,
    )

    /**
     * Selects what this peer is missing.
     *
     * The digest comparison happens before any payload moves, so a packet is
     * never transmitted to a peer that already has it. In a crowd of phones that
     * is the difference between a working mesh and a saturated one.
     */
    private fun buildQueue(digest: BloomDigest) {
        val peer = peerId ?: return

        queue = heldPackets
            .filter { packet ->
                when {
                    digest.mightContain(packet.packetId) -> { skipped++; false }
                    !isForwardable(packet, peer) -> { skipped++; false }
                    else -> true
                }
            }
            // Highest TTL first: those have the most journey left, so they gain
            // the most from being handed on.
            .sortedByDescending { it.ttlRemaining }
            .toMutableList()
    }

    private fun sendNextOrFinish(): BleAction {
        val next = queue.removeFirstOrNull()
            ?: return finish(if (delivered.isEmpty()) "nothing to send" else "queue drained")

        phase = Phase.SENDING
        inFlight = next.packetId

        return BleAction.SendPacket(
            packetId = next.packetId,
            // Wrapped in its checksum before framing, so the receiver can tell a
            // scrambled reassembly from a real report. Fragments arriving intact
            // but in the wrong order is corruption the radio's own CRC cannot see.
            frames = ChunkFraming.encode(PacketEnvelope.wrap(next.encodeBody()), mtu),
        )
    }

    private fun recordAck(packetId: PacketId, code: AckCode) {
        inFlight = null

        if (code.isDelivered) {
            delivered += packetId
            return
        }

        failures[packetId] = code

        // A peer out of room will refuse everything else too; continuing would
        // waste the connection and the battery behind it.
        if (code == AckCode.NO_CAPACITY) {
            queue.clear()
            endedBecause = "peer reported no capacity"
        }
    }

    private fun finish(reason: String): BleAction {
        phase = Phase.DONE
        if (endedBecause.isEmpty()) endedBecause = reason
        return BleAction.Disconnect(endedBecause)
    }
}

/**
 * Wire encoding for a packet body.
 *
 * A deliberately simple field-separated encoding rather than a JSON library: the
 * receiver is a different device, possibly running a different build, and the
 * same reasoning that made the signing canonicalisation explicit applies here.
 *
 * @see docs/06-ble-protocol.md 6.4
 */
object PacketCodec {

    /** ASCII unit separator. No keyboard produces it, so free text cannot contain it. */
    private const val SEP = '\u001F'

    fun encode(packet: MeshPacket): ByteArray {
        val p = packet.payload

        return listOf(
            packet.packetId.value,
            packet.emergencyId.value,
            packet.originDeviceId.value,
            packet.createdAtDeviceMs.toString(),
            packet.hopCount.toString(),
            packet.ttlRemaining.toString(),
            packet.ttlInitial.toString(),
            packet.hmac.orEmpty(),
            p.typeCode,
            p.description.orEmpty().sanitised(),
            p.affectedCount.toString(),
            p.childrenCount.toString(),
            p.elderlyCount.toString(),
            p.mobilityLimitedCount.toString(),
            if (p.isLifeThreatening) "1" else "0",
            p.vulnerabilityNotes.orEmpty().sanitised(),
            p.latitude?.toString().orEmpty(),
            p.longitude?.toString().orEmpty(),
            p.accuracyM?.toString().orEmpty(),
            p.locationProvider.orEmpty(),
            p.capturedAtMs?.toString().orEmpty(),
        ).joinToString(SEP.toString()).toByteArray(Charsets.UTF_8)
    }

    /**
     * Returns null for anything that does not parse.
     *
     * A malformed body is dropped rather than half-decoded: a report with a
     * mangled location or a missing id is worse than no report, because it looks
     * real on an operator's screen.
     */
    fun decode(bytes: ByteArray): MeshPacket? {
        val fields = String(bytes, Charsets.UTF_8).split(SEP)
        if (fields.size < FIELD_COUNT) return null

        return try {
            MeshPacket(
                packetId = PacketId(fields[0]),
                emergencyId = ph.bulig.mesh.model.EmergencyId(fields[1]),
                originDeviceId = DeviceId(fields[2]),
                createdAtDeviceMs = fields[3].toLong(),
                hopCount = fields[4].toInt(),
                ttlRemaining = fields[5].toInt(),
                ttlInitial = fields[6].toInt(),
                hmac = fields[7].ifEmpty { null },
                payload = ph.bulig.mesh.model.EmergencyPayload(
                    typeCode = fields[8],
                    description = fields[9].ifEmpty { null },
                    affectedCount = fields[10].toInt(),
                    childrenCount = fields[11].toInt(),
                    elderlyCount = fields[12].toInt(),
                    mobilityLimitedCount = fields[13].toInt(),
                    isLifeThreatening = fields[14] == "1",
                    vulnerabilityNotes = fields[15].ifEmpty { null },
                    latitude = fields[16].toDoubleOrNull(),
                    longitude = fields[17].toDoubleOrNull(),
                    accuracyM = fields[18].toDoubleOrNull(),
                    locationProvider = fields[19].ifEmpty { null },
                    capturedAtMs = fields[20].toLongOrNull(),
                ),
            )
        } catch (e: Exception) {
            null
        }
    }

    private const val FIELD_COUNT = 21

    /** Strips the separator so a description cannot forge a field boundary. */
    private fun String.sanitised(): String = replace(SEP.toString(), " ")
}

fun MeshPacket.encodeBody(): ByteArray = PacketCodec.encode(this)
