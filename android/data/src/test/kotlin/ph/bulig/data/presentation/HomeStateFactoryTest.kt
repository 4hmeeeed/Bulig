package ph.bulig.data.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ph.bulig.data.model.LocalReport
import ph.bulig.mesh.delivery.ConnectivityState
import ph.bulig.mesh.delivery.DeliveryState
import ph.bulig.mesh.delivery.DeliveryTone
import ph.bulig.mesh.model.DeviceId
import ph.bulig.mesh.model.EmergencyId
import ph.bulig.mesh.model.EmergencyPayload
import ph.bulig.mesh.model.Handoff
import ph.bulig.mesh.model.MeshPacket
import ph.bulig.mesh.model.PacketId

/**
 * What artboard 01 tells a resident, and when.
 *
 * These rules live outside Compose deliberately: a decision that can only be
 * exercised on an emulator is a decision nobody checks.
 */
class HomeStateFactoryTest {

    private val now = 1_787_802_731_000L

    private val labels = mapOf(
        "FLOOD" to TypeLabel("FLOOD", "Flood", "Baha"),
        "MEDICAL" to TypeLabel("MEDICAL", "Medical", "Emerhensya Medikal"),
    )

    private fun report(
        n: Int,
        state: DeliveryState = DeliveryState.SAVED_LOCAL,
        type: String = "FLOOD",
        createdAtMs: Long = now,
        handoffs: Int = 0,
        synced: Boolean = false,
        code: String? = null,
    ) = LocalReport(
        packet = MeshPacket(
            packetId = PacketId("00000000-0000-4000-8000-%012d".format(n)),
            emergencyId = EmergencyId("11111111-0000-4000-8000-%012d".format(n)),
            originDeviceId = DeviceId("this-device"),
            createdAtDeviceMs = createdAtMs,
            payload = EmergencyPayload(typeCode = type),
        ),
        deliveryState = state,
        emergencyCode = code,
        synced = synced,
        handoffs = (1..handoffs).map { Handoff(DeviceId("peer-$it"), now) },
    )

    // --- the banner -------------------------------------------------------

    @Test
    fun `offline with nothing queued reads as offline`() {
        assertEquals(
            ConnectivityState.OFFLINE,
            HomeStateFactory.connectivityState(isOnline = false, isSyncing = false, pendingCount = 0),
        )
    }

    /** OFFLINE and PENDING share a hue, so the distinction has to be right. */
    @Test
    fun `offline with queued reports reads as pending`() {
        assertEquals(
            ConnectivityState.PENDING,
            HomeStateFactory.connectivityState(isOnline = false, isSyncing = false, pendingCount = 3),
        )
    }

    @Test
    fun `an upload in flight reads as syncing even though the device is online`() {
        assertEquals(
            ConnectivityState.SYNCING,
            HomeStateFactory.connectivityState(isOnline = true, isSyncing = true, pendingCount = 2),
        )
    }

    /**
     * Having a signal is not the same as the command center having your report.
     * ONLINE must never be reported as SYNCED.
     */
    @Test
    fun `being online is not the same as being delivered`() {
        val state = HomeStateFactory.connectivityState(
            isOnline = true, isSyncing = false, pendingCount = 0,
        )

        assertEquals(ConnectivityState.ONLINE, state)
        assertFalse(state == ConnectivityState.SYNCED)
    }

    @Test
    fun `online with a queue still says pending`() {
        assertEquals(
            ConnectivityState.PENDING,
            HomeStateFactory.connectivityState(isOnline = true, isSyncing = false, pendingCount = 1),
        )
    }

    @Test
    fun `the banner counts only reports still owed to the server`() {
        val state = HomeStateFactory.build(
            myReports = listOf(
                report(1),
                report(2, state = DeliveryState.DELIVERED, synced = true),
                report(3),
            ),
            carriedForOthers = emptyList(),
            isOnline = false,
            isSyncing = false,
            nearbyPeerCount = 0,
        )

        assertEquals("2 reports waiting to be delivered", state.banner.sentence)
    }

    @Test
    fun `a single pending report is described in the singular`() {
        val state = HomeStateFactory.build(
            myReports = listOf(report(1)),
            carriedForOthers = emptyList(),
            isOnline = false, isSyncing = false, nearbyPeerCount = 0,
        )

        assertEquals("1 report waiting to be delivered", state.banner.sentence)
    }

    // --- mesh strip -------------------------------------------------------

    @Test
    fun `the mesh strip is hidden when there is no mesh to describe`() {
        val alone = HomeStateFactory.build(
            myReports = emptyList(), carriedForOthers = emptyList(),
            isOnline = false, isSyncing = false, nearbyPeerCount = 0,
        )

        assertFalse(alone.showMeshStrip, "an empty mesh strip is noise, not reassurance")
    }

    @Test
    fun `the mesh strip appears once peers are in range`() {
        val state = HomeStateFactory.build(
            myReports = emptyList(), carriedForOthers = emptyList(),
            isOnline = false, isSyncing = false, nearbyPeerCount = 4,
        )

        assertTrue(state.showMeshStrip)
        assertEquals("4 Bulig phones nearby", state.meshStripText)
    }

    @Test
    fun `one nearby phone is described in the singular`() {
        val state = HomeStateFactory.build(
            myReports = emptyList(), carriedForOthers = emptyList(),
            isOnline = false, isSyncing = false, nearbyPeerCount = 1,
        )

        assertEquals("1 Bulig phone nearby", state.meshStripText)
    }

    /** Carrying for neighbours is worth surfacing even with nobody in range now. */
    @Test
    fun `the strip stays visible while carrying reports for other people`() {
        val state = HomeStateFactory.build(
            myReports = emptyList(),
            carriedForOthers = listOf(report(9), report(10)),
            isOnline = false, isSyncing = false, nearbyPeerCount = 0,
        )

        assertTrue(state.showMeshStrip)
        assertEquals(2, state.carryingForOthersCount)
    }

    // --- recent reports ---------------------------------------------------

    @Test
    fun `home shows only the two most recent reports but counts them all`() {
        val state = HomeStateFactory.build(
            myReports = listOf(report(1), report(2), report(3), report(4)),
            carriedForOthers = emptyList(),
            isOnline = true, isSyncing = false, nearbyPeerCount = 0,
        )

        assertEquals(2, state.recentReports.size)
        assertEquals(4, state.totalReportCount, "the section header still shows the true total")
    }

    @Test
    fun `rows carry bilingual labels from the cached type list`() {
        val state = HomeStateFactory.build(
            myReports = listOf(report(1, type = "FLOOD")),
            carriedForOthers = emptyList(),
            isOnline = true, isSyncing = false, nearbyPeerCount = 0,
            typeLabels = labels,
        )

        val row = state.recentReports.single()
        assertEquals("Flood", row.typeLabelEn)
        assertEquals("Baha", row.typeLabelWar)
    }

    /**
     * An unknown type falls back to its code. Ugly, but a resident seeing
     * "FLOOD" is better served than one seeing "Unknown".
     */
    @Test
    fun `an unknown type falls back to its code rather than a placeholder`() {
        val state = HomeStateFactory.build(
            myReports = listOf(report(1, type = "LAHAR")),
            carriedForOthers = emptyList(),
            isOnline = true, isSyncing = false, nearbyPeerCount = 0,
            typeLabels = labels,
        )

        assertEquals("LAHAR", state.recentReports.single().typeLabelEn)
    }

    /**
     * The row's chip comes from the shared formatter, so a relayed report can
     * never be rendered in the confirmed tone on this screen either.
     */
    @Test
    fun `a relayed row is never presented as delivered`() {
        val state = HomeStateFactory.build(
            myReports = listOf(report(1, state = DeliveryState.RELAYED, handoffs = 3)),
            carriedForOthers = emptyList(),
            isOnline = false, isSyncing = false, nearbyPeerCount = 2,
        )

        val row = state.recentReports.single()
        assertEquals(DeliveryTone.IN_MOTION, row.presentation.tone)
        assertTrue(row.presentation.label.contains("3 PHONES"))
        assertTrue(row.presentation.sentence.contains("Not yet delivered"))
    }

    @Test
    fun `a delivered row is presented as confirmed`() {
        val state = HomeStateFactory.build(
            myReports = listOf(
                report(1, state = DeliveryState.DELIVERED, synced = true, code = "BLG-2026-0417"),
            ),
            carriedForOthers = emptyList(),
            isOnline = true, isSyncing = false, nearbyPeerCount = 0,
        )

        val row = state.recentReports.single()
        assertEquals(DeliveryTone.CONFIRMED, row.presentation.tone)
        assertEquals("BLG-2026-0417", row.emergencyCode)
    }

    @Test
    fun `a first launch with no reports is an empty state not an error`() {
        val state = HomeStateFactory.build(
            myReports = emptyList(), carriedForOthers = emptyList(),
            isOnline = false, isSyncing = false, nearbyPeerCount = 0,
        )

        assertTrue(state.recentReports.isEmpty())
        assertEquals(0, state.totalReportCount)
        assertEquals(ConnectivityState.OFFLINE, state.banner.state)
        assertTrue(
            state.banner.sentence.contains("saved and will be relayed"),
            "offline is the normal case here, and must not read as a failure",
        )
    }
}
