package ph.bulig.data.delivery

import ph.bulig.data.model.LocalReport
import ph.bulig.data.store.ReportStore
import ph.bulig.mesh.delivery.DeliveryState
import ph.bulig.mesh.model.PacketId

/**
 * What actually happened, as opposed to what we hope happened.
 *
 * Delivery state advances only when one of these is observed. There is
 * deliberately no "we sent the request" event — dispatching is not evidence.
 */
sealed interface DeliveryEvidence {
    /** A peer acknowledged taking a copy over BLE. */
    data class PeerTookCopy(val handoffCount: Int) : DeliveryEvidence

    /** The server acknowledged holding the packet, in its response body. */
    data class ServerAcknowledged(val emergencyCode: String?, val priorityLevel: String?) :
        DeliveryEvidence

    /** The server reported a responder assignment on a later pull. */
    data class ResponderAssigned(val responderName: String?) : DeliveryEvidence

    data object ResponderEnRoute : DeliveryEvidence
    data object ResponderOnSite : DeliveryEvidence
    data object IncidentResolved : DeliveryEvidence
}

/**
 * Moves a report's delivery state forward, and never backward.
 *
 * The failure this guards against is subtle and would be easy to ship: a report
 * reaches RESOLVED, then a stale mesh event or a slow duplicate arrives and drags
 * it back to RELAYED. The resident would watch a finished emergency reopen
 * itself. Reports arrive late and out of order over a mesh, so this is a
 * question of when, not whether.
 *
 * @see docs/07-offline-sync.md 7.2
 */
class DeliveryStateMachine(private val store: ReportStore) {

    /**
     * Returns the updated report, or the unchanged one when the evidence implies
     * a state the report has already passed.
     */
    fun apply(packetId: PacketId, evidence: DeliveryEvidence): LocalReport? {
        val report = store.get(packetId) ?: return null

        val target = when (evidence) {
            is DeliveryEvidence.PeerTookCopy -> DeliveryState.RELAYED
            is DeliveryEvidence.ServerAcknowledged -> DeliveryState.DELIVERED
            is DeliveryEvidence.ResponderAssigned -> DeliveryState.ASSIGNED
            DeliveryEvidence.ResponderEnRoute -> DeliveryState.EN_ROUTE
            DeliveryEvidence.ResponderOnSite -> DeliveryState.ON_SITE
            DeliveryEvidence.IncidentResolved -> DeliveryState.RESOLVED
        }

        // Late and out-of-order evidence is normal on a mesh. Ignore anything
        // that would move the report backwards.
        if (!report.deliveryState.canAdvanceTo(target)) {
            return applySideEffectsOnly(report, evidence)
        }

        val advanced = when (evidence) {
            is DeliveryEvidence.ServerAcknowledged -> report.copy(
                deliveryState = target,
                emergencyCode = evidence.emergencyCode ?: report.emergencyCode,
                // Server priority is authoritative; the on-device value was provisional.
                priorityLevel = evidence.priorityLevel ?: report.priorityLevel,
                synced = true,
            )

            else -> report.copy(deliveryState = target)
        }

        store.upsert(advanced)
        return advanced
    }

    /**
     * Evidence that cannot advance the state may still carry facts worth keeping.
     *
     * A duplicate server acknowledgement for an already-delivered report, for
     * instance, still confirms the packet is synced — that is a fact about
     * transport, not a claim about progress.
     */
    private fun applySideEffectsOnly(
        report: LocalReport,
        evidence: DeliveryEvidence,
    ): LocalReport {
        if (evidence !is DeliveryEvidence.ServerAcknowledged) return report

        val updated = report.copy(
            synced = true,
            emergencyCode = report.emergencyCode ?: evidence.emergencyCode,
            priorityLevel = report.priorityLevel ?: evidence.priorityLevel,
        )

        if (updated != report) store.upsert(updated)
        return updated
    }

    /**
     * Marks a report as permanently undeliverable.
     *
     * Note that the delivery STATE does not change: the report is still only
     * saved locally, and telling the resident anything else would be a lie. The
     * flag exists to stop the sync worker retrying forever.
     */
    fun markPermanentFailure(packetId: PacketId, reason: String): LocalReport? {
        val report = store.get(packetId) ?: return null
        val updated = report.copy(permanentFailure = reason)
        store.upsert(updated)
        return updated
    }
}
