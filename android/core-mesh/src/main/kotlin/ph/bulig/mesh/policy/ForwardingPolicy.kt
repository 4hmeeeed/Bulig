package ph.bulig.mesh.policy

import ph.bulig.mesh.model.DeviceId
import ph.bulig.mesh.model.MeshPacket

/**
 * Why a packet was or was not handed to a peer.
 *
 * Modelled as an explicit result rather than a boolean so the reason can be
 * logged and shown on the Mesh Status screen. A resident asking "why isn't this
 * sending?" deserves an answer better than silence.
 */
sealed interface ForwardDecision {
    data object Forward : ForwardDecision

    sealed interface Skip : ForwardDecision {
        val reason: String
    }

    data object TtlExpired : Skip {
        override val reason = "TTL reached zero; this packet stops here"
    }

    data object AlreadyHeldByPeer : Skip {
        override val reason = "Peer already holds this packet"
    }

    data object AlreadySentToPeer : Skip {
        override val reason = "Already sent to this peer in this session"
    }

    data object BatteryTooLow : Skip {
        override val reason = "Battery below the relay floor"
    }

    data object PacketTooOld : Skip {
        override val reason = "Packet older than the maximum relay age"
    }

    data object OwnPacketReturning : Skip {
        override val reason = "Peer is where this packet came from"
    }
}

data class RelayConditions(
    val batteryPercent: Int = 100,
    val nowMs: Long,
)

/**
 * Decides whether a held packet should be offered to a given peer.
 *
 * Every rule here exists to protect something finite: TTL protects the airwaves,
 * the battery floor protects the carrier's own ability to call for help, and the
 * age limit stops the mesh carrying reports long past the point of usefulness.
 *
 * @see docs/06-ble-protocol.md 6.6
 */
class ForwardingPolicy(
    private val batteryFloorPercent: Int = DEFAULT_BATTERY_FLOOR,
    private val maxPacketAgeMs: Long = DEFAULT_MAX_AGE_MS,
) {

    fun decide(
        packet: MeshPacket,
        peer: DeviceId,
        peerHolds: (packetId: ph.bulig.mesh.model.PacketId) -> Boolean,
        alreadySentThisSession: Boolean,
        conditions: RelayConditions,
    ): ForwardDecision {
        if (packet.isTerminal) return ForwardDecision.TtlExpired

        // Sending a packet back to the phone that created it wastes a transfer
        // the origin can never benefit from.
        if (peer == packet.originDeviceId) return ForwardDecision.OwnPacketReturning

        if (alreadySentThisSession) return ForwardDecision.AlreadySentToPeer

        if (conditions.batteryPercent < batteryFloorPercent) {
            return ForwardDecision.BatteryTooLow
        }

        if (conditions.nowMs - packet.createdAtDeviceMs > maxPacketAgeMs) {
            return ForwardDecision.PacketTooOld
        }

        // Checked last: it is the most expensive test, and the cheap rules above
        // will already have excluded most candidates.
        if (peerHolds(packet.packetId)) return ForwardDecision.AlreadyHeldByPeer

        return ForwardDecision.Forward
    }

    companion object {
        const val DEFAULT_BATTERY_FLOOR: Int = 15
        const val DEFAULT_MAX_AGE_MS: Long = 24L * 60 * 60 * 1000
    }
}
