package ph.bulig.data.store

import ph.bulig.data.model.LocalReport
import ph.bulig.mesh.model.EmergencyId
import ph.bulig.mesh.model.PacketId

/**
 * Local persistence for reports this phone holds — its own and its neighbours'.
 *
 * Backed by Room (encrypted with SQLCipher) on-device, and by a map in tests.
 * The repository never touches a database directly, which is what keeps the
 * local-first write path verifiable without Android.
 *
 * Every method must complete without a network. That is not an optimisation
 * here; it is the product.
 */
interface ReportStore {
    fun upsert(report: LocalReport)
    fun get(packetId: PacketId): LocalReport?
    fun findByEmergencyId(emergencyId: EmergencyId): LocalReport?
    fun all(): List<LocalReport>

    /** Reports the server has not yet acknowledged and has not permanently refused. */
    fun pendingSync(): List<LocalReport>

    /** Reports this device created, newest first — the "My reports" screen. */
    fun mine(): List<LocalReport>

    /** Reports being carried for other people — the Mesh Status headline. */
    fun carriedForOthers(): List<LocalReport>

    fun delete(packetId: PacketId)
    fun count(): Int
}

class InMemoryReportStore : ReportStore {
    private val reports = LinkedHashMap<PacketId, LocalReport>()

    override fun upsert(report: LocalReport) {
        reports[report.packetId] = report
    }

    override fun get(packetId: PacketId): LocalReport? = reports[packetId]

    override fun findByEmergencyId(emergencyId: EmergencyId): LocalReport? =
        reports.values.firstOrNull { it.emergencyId == emergencyId }

    override fun all(): List<LocalReport> = reports.values.toList()

    override fun pendingSync(): List<LocalReport> = reports.values.filter { it.isPendingSync }

    override fun mine(): List<LocalReport> =
        reports.values.filter { it.isMine }
            .sortedByDescending { it.packet.createdAtDeviceMs }

    override fun carriedForOthers(): List<LocalReport> = reports.values.filter { !it.isMine }

    override fun delete(packetId: PacketId) {
        reports.remove(packetId)
    }

    override fun count(): Int = reports.size
}
