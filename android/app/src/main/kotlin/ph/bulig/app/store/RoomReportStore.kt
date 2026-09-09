package ph.bulig.app.store

import ph.bulig.data.model.LocalReport
import ph.bulig.data.store.ReportRecord
import ph.bulig.data.store.ReportStore
import ph.bulig.mesh.model.EmergencyId
import ph.bulig.mesh.model.PacketId

/**
 * The real [ReportStore], backed by the encrypted database.
 *
 * A thin adapter over [ReportDao]: every mapping decision belongs to
 * [ReportRecord] in `:data`, which is tested. The only judgement here is what to
 * do with a row that will not decode.
 *
 * Blocking calls, deliberately. Room refuses main-thread queries by default and
 * will throw if one reaches it, which is the behaviour we want: it turns a
 * threading mistake into a loud crash in development rather than a silent frame
 * drop on a cheap phone during an emergency. Callers use a background dispatcher.
 */
class RoomReportStore(private val dao: ReportDao) : ReportStore {

    override fun upsert(report: LocalReport) {
        dao.upsert(ReportEntity.from(ReportRecord.from(report)))
    }

    override fun get(packetId: PacketId): LocalReport? =
        dao.get(packetId.value)?.decode()

    override fun findByEmergencyId(emergencyId: EmergencyId): LocalReport? =
        dao.findByEmergencyId(emergencyId.value)?.decode()

    override fun all(): List<LocalReport> = dao.all().decodeAll()

    override fun pendingSync(): List<LocalReport> = dao.pendingSync().decodeAll()

    override fun mine(): List<LocalReport> = dao.mine().decodeAll()

    override fun carriedForOthers(): List<LocalReport> = dao.carriedForOthers().decodeAll()

    override fun delete(packetId: PacketId) = dao.delete(packetId.value)

    /**
     * The raw row count, which may exceed the number of readable reports.
     *
     * Deliberately not filtered by decodability: this is used to decide whether
     * the device is out of room, and an undecodable row still occupies disk.
     */
    override fun count(): Int = dao.count()

    private fun ReportEntity.decode(): LocalReport? = toRecord().toReport()

    /**
     * Skips rows that will not decode rather than failing the whole query.
     *
     * One corrupt row must not stop a resident seeing the other nine, or stop
     * the sync worker uploading them. `ReportRecord` decides what "corrupt"
     * means and is tested on it.
     */
    private fun List<ReportEntity>.decodeAll(): List<LocalReport> = mapNotNull { it.decode() }
}
