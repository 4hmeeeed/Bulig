package ph.bulig.data.model

import ph.bulig.mesh.delivery.DeliveryState
import ph.bulig.mesh.model.EmergencyId
import ph.bulig.mesh.model.MeshPacket
import ph.bulig.mesh.model.PacketId

/**
 * A report as this phone holds it.
 *
 * Wraps the [MeshPacket] — which is what actually travels — with the local
 * bookkeeping the resident's screens need: how far delivery has got, what the
 * server eventually called it, and whether an upload is still owed.
 *
 * On-device this is a Room entity. Here it is a plain data class so the write
 * path, the state machine and the sync coordinator can be tested with no
 * Android dependency at all.
 */
data class LocalReport(
    val packet: MeshPacket,

    /** Advances only on evidence — see [ph.bulig.data.delivery.DeliveryStateMachine]. */
    val deliveryState: DeliveryState = DeliveryState.SAVED_LOCAL,

    /** Assigned by the server on first sync. Null until then, and that is normal. */
    val emergencyCode: String? = null,

    /** Server-authoritative once known; the on-device value is provisional. */
    val priorityLevel: String? = null,

    /** Peers that have taken a copy from this phone, with observed times. */
    val handoffs: List<ph.bulig.mesh.model.Handoff> = emptyList(),

    /** Whether the server has acknowledged holding this packet. */
    val synced: Boolean = false,

    /** Set when the server refuses permanently, so we stop retrying. */
    val permanentFailure: String? = null,

    val attemptCount: Int = 0,
    val lastAttemptAtMs: Long? = null,

    /** True when this device created the report, false when it is carrying it. */
    val isMine: Boolean = true,
) {
    val packetId: PacketId get() = packet.packetId
    val emergencyId: EmergencyId get() = packet.emergencyId

    /**
     * Whether this report still owes the server an upload.
     *
     * A TTL-expired packet is still pending: it can no longer be relayed, but the
     * phone holding it may yet find signal, and dropping it would discard a
     * report already carried across the barangay.
     */
    val isPendingSync: Boolean
        get() = !synced && permanentFailure == null

    /** How many peers have taken a copy — knowable offline, unlike hop count. */
    val handoffCount: Int get() = handoffs.size
}

/** The user's answers, before any identity or signature exists. */
data class ReportDraft(
    val typeCode: String,
    val description: String? = null,
    val affectedCount: Int = 1,
    val childrenCount: Int = 0,
    val elderlyCount: Int = 0,
    val mobilityLimitedCount: Int = 0,
    val isLifeThreatening: Boolean = false,
    val vulnerabilityNotes: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyM: Double? = null,
    val locationProvider: String? = null,
    val capturedAtMs: Long? = null,
) {
    /**
     * A report is valid with almost nothing filled in.
     *
     * Deliberately permissive: the only required field is the emergency type.
     * A report without coordinates is better than no report, and a frightened
     * person should never be blocked by a form.
     */
    fun validationErrors(): List<String> = buildList {
        if (typeCode.isBlank()) add("An emergency type is required.")
        if (affectedCount < 0) add("Affected count cannot be negative.")
        if (childrenCount < 0 || elderlyCount < 0 || mobilityLimitedCount < 0) {
            add("Vulnerability counts cannot be negative.")
        }
        latitude?.let { if (it < -90 || it > 90) add("Latitude is out of range.") }
        longitude?.let { if (it < -180 || it > 180) add("Longitude is out of range.") }
        if (description != null && description.length > 1000) {
            add("Description is longer than 1000 characters.")
        }
    }

    val isValid: Boolean get() = validationErrors().isEmpty()
}
