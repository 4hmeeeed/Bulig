package ph.bulig.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ph.bulig.data.model.ReportDraft
import ph.bulig.data.store.InMemoryReportStore
import ph.bulig.mesh.MutableClock
import ph.bulig.mesh.crypto.CanonicalPacket
import ph.bulig.mesh.crypto.PacketSigner
import ph.bulig.mesh.delivery.DeliveryState
import ph.bulig.mesh.model.DeviceId
import ph.bulig.mesh.model.EmergencyId
import ph.bulig.mesh.model.MeshPacket
import ph.bulig.mesh.model.PacketId

/**
 * The local-first write path.
 *
 * @see docs/07-offline-sync.md 7.1
 */
class ReportRepositoryTest {

    private val clock = MutableClock(1_787_802_731_000)
    private val store = InMemoryReportStore()
    private val key = ByteArray(32) { it.toByte() }
    private val deviceId = DeviceId("1f2e3d4c-5b6a-4798-8877-000000000003")

    private var counter = 0
    private val ids = IdGenerator { "00000000-0000-4000-8000-%012d".format(++counter) }

    private val repository = ReportRepository(
        deviceId = deviceId,
        store = store,
        signer = PacketSigner(key),
        clock = clock,
        ids = ids,
    )

    private val draft = ReportDraft(
        typeCode = "FLOOD",
        description = "Tubig abot na sa hita sa Purok 4.",
        affectedCount = 5,
        childrenCount = 2,
        elderlyCount = 1,
        mobilityLimitedCount = 1,
        isLifeThreatening = true,
        latitude = 11.24186,
        longitude = 125.00417,
        accuracyM = 38.0,
        locationProvider = "gps",
    )

    /**
     * The architecture's central claim, as a test.
     *
     * The repository is constructed with no network client and no connectivity
     * check — it cannot reach a server even if it wanted to. If someone later
     * adds an API call to the write path, this test stops compiling, which is
     * exactly the alarm we want.
     */
    @Test
    fun `saving a report succeeds with no network of any kind available`() {
        val result = repository.save(draft)

        assertTrue(result is SaveResult.Saved)
        val report = (result as SaveResult.Saved).report

        assertEquals(DeliveryState.SAVED_LOCAL, report.deliveryState)
        assertEquals(1, store.count(), "the report is committed locally, immediately")
        assertNull(report.emergencyCode, "the code comes from the server; there isn't one yet")
    }

    @Test
    fun `identifiers are minted on the device`() {
        val report = (repository.save(draft) as SaveResult.Saved).report

        assertEquals("00000000-0000-4000-8000-000000000001", report.packetId.value)
        assertEquals("00000000-0000-4000-8000-000000000002", report.emergencyId.value)
        assertEquals(deviceId, report.packet.originDeviceId)
    }

    @Test
    fun `a saved report is signed and verifiable`() {
        val report = (repository.save(draft) as SaveResult.Saved).report

        assertNotNull(report.packet.hmac)
        assertTrue(
            CanonicalPacket.verify(report.packet, key, report.packet.hmac!!),
            "the origin's signature must verify against its own key",
        )
    }

    /**
     * Registration requires connectivity, and a phone in a flood may never have
     * had any. Blocking reporting on it would reintroduce the exact dependency
     * this system exists to remove.
     */
    @Test
    fun `an unregistered device can still file a report`() {
        val unregistered = ReportRepository(
            deviceId = deviceId,
            store = InMemoryReportStore(),
            signer = PacketSigner(null),
            clock = clock,
            ids = ids,
        )

        val result = unregistered.save(draft)

        assertTrue(result is SaveResult.Saved)
        assertNull(
            (result as SaveResult.Saved).report.packet.hmac,
            "the server records this as unverifiable, not invalid",
        )
    }

    @Test
    fun `the draft carries through to the packet payload intact`() {
        val payload = (repository.save(draft) as SaveResult.Saved).report.packet.payload

        assertEquals("FLOOD", payload.typeCode)
        assertEquals(5, payload.affectedCount)
        assertEquals(2, payload.childrenCount)
        assertEquals(1, payload.elderlyCount)
        assertEquals(1, payload.mobilityLimitedCount)
        assertTrue(payload.isLifeThreatening)
        assertEquals(11.24186, payload.latitude)
        assertEquals(38.0, payload.accuracyM)
    }

    /**
     * A report with only a type is valid. Everything else is optional, because a
     * frightened person must never be blocked by a form — and a report without
     * coordinates is better than no report.
     */
    @Test
    fun `a report needs nothing but an emergency type`() {
        val result = repository.save(ReportDraft(typeCode = "MEDICAL"))

        assertTrue(result is SaveResult.Saved)
    }

    @Test
    fun `invalid drafts are refused with reasons`() {
        val result = repository.save(ReportDraft(typeCode = "", affectedCount = -1))

        assertTrue(result is SaveResult.Invalid)
        val errors = (result as SaveResult.Invalid).errors
        assertEquals(2, errors.size)
        assertEquals(0, store.count(), "nothing invalid reaches storage")
    }

    @Test
    fun `out of range coordinates are refused`() {
        val result = repository.save(draft.copy(latitude = 200.0))

        assertTrue(result is SaveResult.Invalid)
    }

    // --- carrying other people's reports ----------------------------------

    private fun peerPacket(n: Int) = MeshPacket(
        packetId = PacketId("aaaaaaaa-0000-4000-8000-%012d".format(n)),
        emergencyId = EmergencyId("bbbbbbbb-0000-4000-8000-%012d".format(n)),
        originDeviceId = DeviceId("peer-device-$n"),
        createdAtDeviceMs = clock.nowMs(),
        payload = ph.bulig.mesh.model.EmergencyPayload(typeCode = "FIRE"),
    )

    @Test
    fun `a report from a peer is stored and marked as not mine`() {
        val report = repository.acceptFromPeer(peerPacket(1))

        assertNotNull(report)
        assertTrue(!report.isMine)
        assertEquals(1, repository.carriedForOthers().size)
        assertEquals(0, repository.myReports().size)
    }

    /** Dedup on the origin-minted packet id, so a looping copy is recognised. */
    @Test
    fun `the same peer packet is not stored twice`() {
        val packet = peerPacket(1)

        assertNotNull(repository.acceptFromPeer(packet))
        assertNull(repository.acceptFromPeer(packet), "a second copy is a duplicate")

        assertEquals(1, store.count())
    }

    @Test
    fun `handoffs are recorded with observed times`() {
        val report = (repository.save(draft) as SaveResult.Saved).report
        clock.advanceBy(120_000)

        val updated = repository.recordHandoff(report.packetId, DeviceId("peer-A"))

        assertNotNull(updated)
        assertEquals(1, updated.handoffCount)
        assertEquals(clock.nowMs(), updated.handoffs.single().atMs)
    }

    /** Meeting the same peer again must not inflate "3 phones took a copy". */
    @Test
    fun `re-encountering a peer does not double count the handoff`() {
        val report = (repository.save(draft) as SaveResult.Saved).report

        repository.recordHandoff(report.packetId, DeviceId("peer-A"))
        repository.recordHandoff(report.packetId, DeviceId("peer-A"))
        repository.recordHandoff(report.packetId, DeviceId("peer-B"))

        assertEquals(2, repository.find(report.packetId)!!.handoffCount)
    }

    @Test
    fun `my reports are listed newest first`() {
        repository.save(draft)
        clock.advanceBy(60_000)
        repository.save(draft.copy(typeCode = "FIRE"))

        val mine = repository.myReports()

        assertEquals(2, mine.size)
        assertEquals("FIRE", mine.first().packet.payload.typeCode)
    }

    @Test
    fun `a freshly saved report is pending sync`() {
        val report = (repository.save(draft) as SaveResult.Saved).report

        assertTrue(report.isPendingSync)
        assertEquals(1, repository.pendingSync().size)
    }
}
