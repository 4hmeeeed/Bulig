package ph.bulig.data.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ph.bulig.data.model.LocalReport
import ph.bulig.mesh.delivery.DeliveryState
import ph.bulig.mesh.delivery.DeliveryTone
import ph.bulig.mesh.model.DeviceId
import ph.bulig.mesh.model.EmergencyId
import ph.bulig.mesh.model.EmergencyPayload
import ph.bulig.mesh.model.Handoff
import ph.bulig.mesh.model.MeshPacket
import ph.bulig.mesh.model.PacketId

/** Artboard 07 — My reports. */
class MyReportsStateTest {

    private val now = 1_787_802_731_000L
    private val hour = 3_600_000L

    private fun report(
        n: Int,
        state: DeliveryState,
        ageHours: Long,
        type: String = "FLOOD",
        handoffs: Int = 0,
    ) = LocalReport(
        packet = MeshPacket(
            packetId = PacketId("00000000-0000-4000-8000-%012d".format(n)),
            emergencyId = EmergencyId("11111111-0000-4000-8000-%012d".format(n)),
            originDeviceId = DeviceId("this-device"),
            createdAtDeviceMs = now - ageHours * hour,
            payload = EmergencyPayload(typeCode = type),
        ),
        deliveryState = state,
        synced = state.isConfirmedByCommandCenter,
        handoffs = (1..handoffs).map { Handoff(DeviceId("peer-$it"), now) },
    )

    private fun ids(reports: List<LocalReport>) =
        reports.map { it.packetId.value.takeLast(1) }

    /**
     * The rule the design states explicitly, and the one most likely to be lost
     * in a naive `sortedByDescending { createdAt }`.
     */
    @Test
    fun `an undelivered report never sinks below a delivered one`() {
        val sorted = MyReportsStateFactory.sort(
            listOf(
                report(1, DeliveryState.RESOLVED, ageHours = 0),      // newest, but done
                report(2, DeliveryState.SAVED_LOCAL, ageHours = 48),  // oldest, still stuck
                report(3, DeliveryState.DELIVERED, ageHours = 1),
                report(4, DeliveryState.RELAYED, ageHours = 24),
            )
        )

        assertEquals(
            listOf("2", "4", "3", "1"), ids(sorted),
            "reports the resident can still act on belong at the top",
        )
    }

    @Test
    fun `reports in the same delivery state are newest first`() {
        val sorted = MyReportsStateFactory.sort(
            listOf(
                report(1, DeliveryState.SAVED_LOCAL, ageHours = 10),
                report(2, DeliveryState.SAVED_LOCAL, ageHours = 1),
                report(3, DeliveryState.SAVED_LOCAL, ageHours = 5),
            )
        )

        assertEquals(listOf("2", "3", "1"), ids(sorted))
    }

    /** Resolved sinks furthest: it needs nothing from anybody. */
    @Test
    fun `resolved reports sort last even when they are the newest`() {
        val sorted = MyReportsStateFactory.sort(
            listOf(
                report(1, DeliveryState.RESOLVED, ageHours = 0),
                report(2, DeliveryState.ON_SITE, ageHours = 100),
            )
        )

        assertEquals(listOf("2", "1"), ids(sorted))
    }

    @Test
    fun `the full delivery vocabulary is representable in one list`() {
        val state = MyReportsStateFactory.build(
            reports = listOf(
                report(1, DeliveryState.RELAYED, ageHours = 1, handoffs = 3),
                report(2, DeliveryState.SAVED_LOCAL, ageHours = 2),
                report(3, DeliveryState.DELIVERED, ageHours = 20),
                report(4, DeliveryState.RESOLVED, ageHours = 30),
            ),
            isOnline = false,
            isSyncing = false,
        )

        val tones = state.rows.map { it.presentation.tone }

        assertEquals(4, state.totalCount)
        assertTrue(tones.contains(DeliveryTone.NEUTRAL))
        assertTrue(tones.contains(DeliveryTone.IN_MOTION))
        assertTrue(tones.contains(DeliveryTone.CONFIRMED))
    }

    /** The chip is never the only explanation on this screen either. */
    @Test
    fun `every row carries a plain language sentence`() {
        val state = MyReportsStateFactory.build(
            reports = listOf(
                report(1, DeliveryState.RELAYED, ageHours = 1, handoffs = 3),
                report(2, DeliveryState.SAVED_LOCAL, ageHours = 2),
            ),
            isOnline = false, isSyncing = false,
        )

        state.rows.forEach {
            assertTrue(it.presentation.sentence.length > 20, "bare chip on ${it.packetId}")
        }
    }

    @Test
    fun `a relayed row names how many phones took a copy`() {
        val state = MyReportsStateFactory.build(
            reports = listOf(report(1, DeliveryState.RELAYED, ageHours = 1, handoffs = 3)),
            isOnline = false, isSyncing = false,
        )

        val row = state.rows.single()
        assertTrue(row.presentation.label.contains("3 PHONES"))
        assertEquals(DeliveryTone.IN_MOTION, row.presentation.tone)
    }

    @Test
    fun `the banner counts only what is still owed to the server`() {
        val state = MyReportsStateFactory.build(
            reports = listOf(
                report(1, DeliveryState.SAVED_LOCAL, ageHours = 1),
                report(2, DeliveryState.RELAYED, ageHours = 2),
                report(3, DeliveryState.RESOLVED, ageHours = 3),
            ),
            isOnline = false, isSyncing = false,
        )

        assertEquals("2 reports waiting to be delivered", state.banner.sentence)
    }

    @Test
    fun `an empty list reads as reassurance rather than an error`() {
        val state = MyReportsStateFactory.build(
            reports = emptyList(), isOnline = true, isSyncing = false,
        )

        assertTrue(state.isEmpty)
        assertEquals("You have not filed any reports.", state.emptyMessage)
    }
}
