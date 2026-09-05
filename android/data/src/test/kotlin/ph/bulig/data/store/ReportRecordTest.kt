package ph.bulig.data.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ph.bulig.data.model.LocalReport
import ph.bulig.mesh.delivery.DeliveryState
import ph.bulig.mesh.model.DeviceId
import ph.bulig.mesh.model.EmergencyId
import ph.bulig.mesh.model.EmergencyPayload
import ph.bulig.mesh.model.Handoff
import ph.bulig.mesh.model.MeshPacket
import ph.bulig.mesh.model.PacketId

/**
 * The database mapping, tested without a database.
 *
 * Room lives in `:app` and cannot be compiled here, so the Room entity is a
 * field-for-field mirror of `ReportRecord` carrying annotations and nothing
 * else. That makes this file the only place a dropped column can be caught
 * before a phone silently loses part of a report.
 */
class ReportRecordTest {

    private val now = 1_787_802_731_000L

    /** Deliberately maximal: every optional field populated, so none can be lost quietly. */
    private val fullReport = LocalReport(
        packet = MeshPacket(
            packetId = PacketId("00000000-0000-4000-8000-000000000001"),
            emergencyId = EmergencyId("11111111-0000-4000-8000-000000000001"),
            originDeviceId = DeviceId("22222222-0000-4000-8000-000000000001"),
            createdAtDeviceMs = now,
            payload = EmergencyPayload(
                typeCode = "FLOOD",
                description = "Taas na an tubig, aada kami ha atop",
                affectedCount = 5,
                childrenCount = 2,
                elderlyCount = 1,
                mobilityLimitedCount = 1,
                isLifeThreatening = true,
                vulnerabilityNotes = "one on oxygen",
                latitude = 11.2447321,
                longitude = 125.0048119,
                accuracyM = 38.5,
                locationProvider = "gps",
                capturedAtMs = now - 4_000,
            ),
            hmac = "f8c462f8b8f3d32fa09a8431202b448b",
            hopCount = 3,
            ttlRemaining = 7,
            ttlInitial = 10,
            routePath = listOf(DeviceId("peer-a"), DeviceId("peer-b"), DeviceId("peer-c")),
        ),
        deliveryState = DeliveryState.RELAYED,
        emergencyCode = "BLG-2026-0041",
        priorityLevel = "CRITICAL",
        handoffs = listOf(
            Handoff(DeviceId("phone-7C4A"), now + 1_000),
            Handoff(DeviceId("phone-B119"), now + 2_000),
        ),
        synced = false,
        permanentFailure = null,
        attemptCount = 2,
        lastAttemptAtMs = now + 9_000,
        isMine = true,
    )

    private fun roundTrip(report: LocalReport): LocalReport {
        val back = ReportRecord.from(report).toReport()
        assertNotNull(back, "a report failed to survive the round trip")
        return back
    }

    /** The whole point of this class in one assertion. */
    @Test
    fun `a fully populated report survives the round trip unchanged`() {
        assertEquals(fullReport, roundTrip(fullReport))
    }

    @Test
    fun `a minimal report survives the round trip unchanged`() {
        val minimal = LocalReport(
            packet = MeshPacket(
                packetId = PacketId("p"),
                emergencyId = EmergencyId("e"),
                originDeviceId = DeviceId("d"),
                createdAtDeviceMs = now,
                payload = EmergencyPayload(typeCode = "OTHER"),
            )
        )

        assertEquals(minimal, roundTrip(minimal))
    }

    /**
     * The signature is what lets the server tell a genuine report from a forged
     * one. Losing it in storage would turn every stored report into
     * INVALID_HMAC on the next sync.
     */
    @Test
    fun `the signature survives storage`() {
        assertEquals(fullReport.packet.hmac, roundTrip(fullReport).packet.hmac)
    }

    /**
     * A dropped ttlRemaining would resurrect a packet that had stopped
     * travelling, and a dropped hopCount would lose the evidence of how far it
     * got. Both are mutable, which is exactly why they are easy to lose.
     */
    @Test
    fun `the relay counters survive storage`() {
        val back = roundTrip(fullReport)

        assertEquals(3, back.packet.hopCount)
        assertEquals(7, back.packet.ttlRemaining)
        assertEquals(10, back.packet.ttlInitial)
    }

    @Test
    fun `a terminal packet is still stored and still reads as terminal`() {
        val terminal = fullReport.copy(
            packet = fullReport.packet.copy(ttlRemaining = 0)
        )

        val back = roundTrip(terminal)
        assertTrue(back.packet.isTerminal)
        assertTrue(back.isPendingSync, "a TTL-expired report is still owed to the server")
    }

    /** Coordinates at full precision: a rounded pin is a rescue team at the wrong house. */
    @Test
    fun `coordinates keep their precision`() {
        val back = roundTrip(fullReport)

        assertEquals(11.2447321, back.packet.payload.latitude)
        assertEquals(125.0048119, back.packet.payload.longitude)
        assertEquals(38.5, back.packet.payload.accuracyM)
    }

    @Test
    fun `handoffs survive with their peers and their times`() {
        val back = roundTrip(fullReport)

        assertEquals(2, back.handoffs.size)
        assertEquals(DeviceId("phone-7C4A"), back.handoffs.first().peerId)
        assertEquals(now + 2_000, back.handoffs.last().atMs)
    }

    @Test
    fun `the route path survives`() {
        assertEquals(
            listOf(DeviceId("peer-a"), DeviceId("peer-b"), DeviceId("peer-c")),
            roundTrip(fullReport).packet.routePath,
        )
    }

    @Test
    fun `every delivery state round-trips`() {
        DeliveryState.entries.forEach { state ->
            val back = roundTrip(fullReport.copy(deliveryState = state))
            assertEquals(state, back.deliveryState, "$state did not survive storage")
        }
    }

    @Test
    fun `a description containing quotes and newlines survives`() {
        val awkward = "she said \"we are on the roof\"\nsecond line\ttab"
        val back = roundTrip(
            fullReport.copy(
                packet = fullReport.packet.copy(
                    payload = fullReport.packet.payload.copy(description = awkward)
                )
            )
        )

        assertEquals(awkward, back.packet.payload.description)
    }

    @Test
    fun `sync bookkeeping survives`() {
        val failed = fullReport.copy(
            synced = true, permanentFailure = "INVALID_HMAC", attemptCount = 7,
        )
        val back = roundTrip(failed)

        assertTrue(back.synced)
        assertEquals("INVALID_HMAC", back.permanentFailure)
        assertEquals(7, back.attemptCount)
        assertTrue(!back.isPendingSync, "a permanently failed report is still being retried")
    }

    @Test
    fun `a report carried for someone else stays marked as theirs`() {
        assertTrue(!roundTrip(fullReport.copy(isMine = false)).isMine)
    }

    // --- corrupt rows ------------------------------------------------------

    /**
     * A resident whose database has one bad row must still be able to open the
     * app and file a new emergency. Losing one report is bad; losing the app
     * during a typhoon is worse.
     */
    @Test
    fun `a row with an unknown delivery state is skipped rather than crashing`() {
        val corrupt = ReportRecord.from(fullReport).copy(deliveryState = "TELEPORTED")

        assertNull(corrupt.toReport())
    }

    @Test
    fun `a row with a blank identifier is skipped`() {
        assertNull(ReportRecord.from(fullReport).copy(packetId = "").toReport())
    }

    @Test
    fun `a row with an impossible ttl is skipped`() {
        assertNull(ReportRecord.from(fullReport).copy(ttlRemaining = -1).toReport())
    }

    /**
     * Routing evidence is not the report. Unreadable handoffs cost a hop log;
     * discarding the whole report over them would cost a rescue.
     */
    @Test
    fun `unreadable handoffs lose the hop log but keep the report`() {
        val back = ReportRecord.from(fullReport).copy(handoffsJson = "{not json").toReport()

        assertNotNull(back)
        assertTrue(back.handoffs.isEmpty())
        assertEquals(fullReport.packetId, back.packetId)
        assertEquals(fullReport.packet.payload.description, back.packet.payload.description)
    }

    @Test
    fun `an unreadable route path keeps the report`() {
        val back = ReportRecord.from(fullReport).copy(routePathJson = "!!").toReport()

        assertNotNull(back)
        assertTrue(back.packet.routePath.isEmpty())
    }

    /**
     * Guards the mirror itself: if a field is added to LocalReport and not to
     * ReportRecord, the round-trip equality tests above start failing — but only
     * if this reminder keeps the column count honest.
     */
    @Test
    fun `the record carries every column the entity must mirror`() {
        // Java reflection rather than kotlin-reflect, which is not on the test
        // classpath and would be a dependency added for one assertion.
        val columns = ReportRecord::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .size

        assertEquals(
            31, columns,
            "ReportRecord gained or lost a column — update the Room entity in :app to match",
        )
    }
}
