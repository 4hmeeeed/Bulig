package ph.bulig.mesh.model

/**
 * The reported emergency itself: the part a relay carries but must not alter.
 *
 * Field names and types mirror the sync API contract in docs/05-api-contract.md
 * so the same object can be signed, relayed, and uploaded without translation.
 */
data class EmergencyPayload(
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
)
