package ph.bulig.data.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import ph.bulig.data.model.LocalReport
import ph.bulig.mesh.delivery.DeliveryState
import ph.bulig.mesh.model.DeviceId
import ph.bulig.mesh.model.EmergencyId
import ph.bulig.mesh.model.EmergencyPayload
import ph.bulig.mesh.model.Handoff
import ph.bulig.mesh.model.MeshPacket
import ph.bulig.mesh.model.PacketId

/**
 * A [LocalReport] flattened to primitives, ready for a database row.
 *
 * This exists so the mapping can be **tested without Room**. Room lives in
 * `:app`, which cannot be compiled in the development container, so an entity
 * class carrying this logic would be code nobody could run until it reached a
 * phone. The Room entity is instead a field-for-field mirror of this class with
 * annotations and nothing else — if a column is wrong, the failure shows up in
 * `ReportRecordTest` rather than as a silently empty report on a handset.
 *
 * The round trip is what matters: a report written to disk and read back must be
 * the same report. A dropped `hmac` would make the server reject it; a dropped
 * `ttlRemaining` would resurrect a packet that had stopped travelling.
 *
 * @see docs/07-offline-sync.md 7.1
 */
data class ReportRecord(
    // --- identity, never rewritten -------------------------------------
    val packetId: String,
    val emergencyId: String,
    val originDeviceId: String,
    val createdAtDeviceMs: Long,

    // --- mutable relay fields ------------------------------------------
    val hopCount: Int,
    val ttlRemaining: Int,
    val ttlInitial: Int,

    val hmac: String?,
    val routePathJson: String,

    // --- payload -------------------------------------------------------
    val typeCode: String,
    val description: String?,
    val affectedCount: Int,
    val childrenCount: Int,
    val elderlyCount: Int,
    val mobilityLimitedCount: Int,
    val isLifeThreatening: Boolean,
    val vulnerabilityNotes: String?,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyM: Double?,
    val locationProvider: String?,
    val capturedAtMs: Long?,

    // --- local bookkeeping ---------------------------------------------
    val deliveryState: String,
    val emergencyCode: String?,
    val priorityLevel: String?,
    val handoffsJson: String,
    val synced: Boolean,
    val permanentFailure: String?,
    val attemptCount: Int,
    val lastAttemptAtMs: Long?,
    val isMine: Boolean,
) {

    /**
     * Returns null when the row cannot be turned back into a report.
     *
     * A corrupt row is skipped rather than crashing the app on launch. A
     * resident whose database has one bad row must still be able to open the
     * app and file a new emergency — losing one report is bad, losing the app
     * during a typhoon is worse.
     */
    fun toReport(): LocalReport? = try {
        LocalReport(
            packet = MeshPacket(
                packetId = PacketId(packetId),
                emergencyId = EmergencyId(emergencyId),
                originDeviceId = DeviceId(originDeviceId),
                createdAtDeviceMs = createdAtDeviceMs,
                payload = EmergencyPayload(
                    typeCode = typeCode,
                    description = description,
                    affectedCount = affectedCount,
                    childrenCount = childrenCount,
                    elderlyCount = elderlyCount,
                    mobilityLimitedCount = mobilityLimitedCount,
                    isLifeThreatening = isLifeThreatening,
                    vulnerabilityNotes = vulnerabilityNotes,
                    latitude = latitude,
                    longitude = longitude,
                    accuracyM = accuracyM,
                    locationProvider = locationProvider,
                    capturedAtMs = capturedAtMs,
                ),
                hmac = hmac,
                hopCount = hopCount,
                ttlRemaining = ttlRemaining,
                ttlInitial = ttlInitial,
                routePath = decodeRoute(routePathJson),
            ),
            deliveryState = DeliveryState.valueOf(deliveryState),
            emergencyCode = emergencyCode,
            priorityLevel = priorityLevel,
            handoffs = decodeHandoffs(handoffsJson),
            synced = synced,
            permanentFailure = permanentFailure,
            attemptCount = attemptCount,
            lastAttemptAtMs = lastAttemptAtMs,
            isMine = isMine,
        )
    } catch (e: Exception) {
        null
    }

    companion object {

        private val json = Json { ignoreUnknownKeys = true }

        @Serializable
        private data class HandoffRow(val peerId: String, val atMs: Long)

        fun from(report: LocalReport): ReportRecord {
            val packet = report.packet
            val p = packet.payload

            return ReportRecord(
                packetId = packet.packetId.value,
                emergencyId = packet.emergencyId.value,
                originDeviceId = packet.originDeviceId.value,
                createdAtDeviceMs = packet.createdAtDeviceMs,
                hopCount = packet.hopCount,
                ttlRemaining = packet.ttlRemaining,
                ttlInitial = packet.ttlInitial,
                hmac = packet.hmac,
                routePathJson = json.encodeToString(
                    ListSerializer(String.serializer()),
                    packet.routePath.map { it.value },
                ),
                typeCode = p.typeCode,
                description = p.description,
                affectedCount = p.affectedCount,
                childrenCount = p.childrenCount,
                elderlyCount = p.elderlyCount,
                mobilityLimitedCount = p.mobilityLimitedCount,
                isLifeThreatening = p.isLifeThreatening,
                vulnerabilityNotes = p.vulnerabilityNotes,
                latitude = p.latitude,
                longitude = p.longitude,
                accuracyM = p.accuracyM,
                locationProvider = p.locationProvider,
                capturedAtMs = p.capturedAtMs,
                deliveryState = report.deliveryState.name,
                emergencyCode = report.emergencyCode,
                priorityLevel = report.priorityLevel,
                handoffsJson = json.encodeToString(
                    ListSerializer(HandoffRow.serializer()),
                    report.handoffs.map { HandoffRow(it.peerId.value, it.atMs) },
                ),
                synced = report.synced,
                permanentFailure = report.permanentFailure,
                attemptCount = report.attemptCount,
                lastAttemptAtMs = report.lastAttemptAtMs,
                isMine = report.isMine,
            )
        }

        /** An unreadable route is empty rather than fatal: it is routing evidence, not the report. */
        private fun decodeRoute(text: String): List<DeviceId> = try {
            json.decodeFromString(
                ListSerializer(String.serializer()), text,
            ).map { DeviceId(it) }
        } catch (e: Exception) {
            emptyList()
        }

        private fun decodeHandoffs(text: String): List<Handoff> = try {
            json.decodeFromString(
                ListSerializer(HandoffRow.serializer()), text,
            ).map { Handoff(DeviceId(it.peerId), it.atMs) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
