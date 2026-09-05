package ph.bulig.data.repository

import ph.bulig.data.model.LocalReport
import ph.bulig.data.model.ReportDraft
import ph.bulig.data.store.ReportStore
import ph.bulig.mesh.Clock
import ph.bulig.mesh.crypto.PacketSigner
import ph.bulig.mesh.model.DeviceId
import ph.bulig.mesh.model.EmergencyId
import ph.bulig.mesh.model.EmergencyPayload
import ph.bulig.mesh.model.Handoff
import ph.bulig.mesh.model.MeshPacket
import ph.bulig.mesh.model.PacketId

/** Where identifiers come from. Injected so tests are deterministic. */
fun interface IdGenerator {
    fun newUuid(): String

    companion object {
        val RANDOM = IdGenerator { java.util.UUID.randomUUID().toString() }
    }
}

sealed interface SaveResult {
    data class Saved(val report: LocalReport) : SaveResult
    data class Invalid(val errors: List<String>) : SaveResult
}

/**
 * The local-first write path.
 *
 * The whole architecture turns on one property, asserted in the tests: **saving
 * a report never touches the network.** The resident is told their report is
 * safe after a local commit, not after a server round-trip — because during a
 * typhoon there is no server round-trip to wait for.
 *
 * Nothing in this class may take a network client, a connectivity check, or a
 * suspending call that could block on IO. If a future change needs one, that is
 * the signal that it belongs in the sync layer instead.
 *
 * @see docs/07-offline-sync.md 7.1
 */
class ReportRepository(
    private val deviceId: DeviceId,
    private val store: ReportStore,
    private val signer: PacketSigner,
    private val clock: Clock = Clock.SYSTEM,
    private val ids: IdGenerator = IdGenerator.RANDOM,
    private val defaultTtl: Int = MeshPacket.DEFAULT_TTL,
) {

    /**
     * Commits a resident's report to local storage.
     *
     * Identifiers are minted here, on the device: a phone with no connectivity
     * cannot ask a server for one, so the device owns identity and the server
     * enforces uniqueness on it later.
     */
    fun save(draft: ReportDraft): SaveResult {
        val errors = draft.validationErrors()
        if (errors.isNotEmpty()) return SaveResult.Invalid(errors)

        val packet = signer.sign(
            MeshPacket(
                packetId = PacketId(ids.newUuid()),
                emergencyId = EmergencyId(ids.newUuid()),
                originDeviceId = deviceId,
                createdAtDeviceMs = clock.nowMs(),
                payload = draft.toPayload(),
                ttlRemaining = defaultTtl,
                ttlInitial = defaultTtl,
            )
        )

        val report = LocalReport(packet = packet, isMine = true)
        store.upsert(report)

        return SaveResult.Saved(report)
    }

    /**
     * Stores a report received from a peer.
     *
     * Deduplicates on `packet_id` — the identifier minted once at the origin and
     * never rewritten — so a copy that loops back is recognised rather than
     * stored twice. Returns null when the packet was already known.
     */
    fun acceptFromPeer(packet: MeshPacket): LocalReport? {
        if (store.get(packet.packetId) != null) return null

        val report = LocalReport(
            packet = packet,
            isMine = packet.originDeviceId == deviceId,
        )
        store.upsert(report)

        return report
    }

    /** Records that a peer took a copy, at a time this device observed. */
    fun recordHandoff(packetId: PacketId, peer: DeviceId): LocalReport? {
        val existing = store.get(packetId) ?: return null

        // A peer that already has it must not inflate the count on a re-encounter.
        if (existing.handoffs.any { it.peerId == peer }) return existing

        val updated = existing.copy(
            handoffs = existing.handoffs + Handoff(peer, clock.nowMs()),
        )
        store.upsert(updated)

        return updated
    }

    fun myReports(): List<LocalReport> = store.mine()

    fun carriedForOthers(): List<LocalReport> = store.carriedForOthers()

    fun pendingSync(): List<LocalReport> = store.pendingSync()

    fun find(packetId: PacketId): LocalReport? = store.get(packetId)

    private fun ReportDraft.toPayload() = EmergencyPayload(
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
    )
}
