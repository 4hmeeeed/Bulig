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

/** Artboard 08 — the delivery timeline. */
class ReportDetailStateTest {

    private val now = 1_787_802_731_000L

    private fun report(
        state: DeliveryState,
        handoffs: List<Handoff> = emptyList(),
        payload: EmergencyPayload = EmergencyPayload(typeCode = "FLOOD", affectedCount = 5),
    ) = LocalReport(
        packet = MeshPacket(
            packetId = PacketId("00000000-0000-4000-8000-000000000001"),
            emergencyId = EmergencyId("11111111-0000-4000-8000-000000000001"),
            originDeviceId = DeviceId("this-phone"),
            createdAtDeviceMs = now,
            payload = payload,
        ),
        deliveryState = state,
        synced = state.isConfirmedByCommandCenter,
        handoffs = handoffs,
    )

    private fun steps(state: DeliveryState, handoffs: List<Handoff> = emptyList()) =
        ReportDetailStateFactory.build(report(state, handoffs)).steps

    // --- the honesty rule -------------------------------------------------

    /**
     * The rule the design states in bold: future steps are labelled "Not yet",
     * never pre-filled with optimistic ticks. A faint tick reads as *done* to
     * somebody scanning a screen in a panic.
     */
    @Test
    fun `every step the report has not reached says Not yet`() {
        steps(DeliveryState.SAVED_LOCAL).filter { it.isNotYet }.forEach {
            assertTrue(
                it.detail.startsWith("Not yet"),
                "step ${it.ordinal} does not say Not yet: '${it.detail}'",
            )
        }
    }

    @Test
    fun `a freshly saved report has reached only the first step`() {
        val timeline = steps(DeliveryState.SAVED_LOCAL)

        assertEquals(StepStatus.CURRENT, timeline[0].status)
        assertTrue(timeline.drop(1).all { it.isNotYet }, "a saved report claimed later progress")
    }

    /**
     * A green glow under the word "Not yet" would undo the word above it, so an
     * unreached step is never tinted with the colour it will eventually take.
     */
    @Test
    fun `an unreached step is never tinted with its eventual tone`() {
        steps(DeliveryState.SAVED_LOCAL).filter { it.isNotYet }.forEach {
            assertEquals(
                DeliveryTone.NEUTRAL, it.tone,
                "step ${it.ordinal} is tinted as though it had happened",
            )
        }
    }

    @Test
    fun `a relayed report has not reached the delivered step`() {
        val timeline = steps(DeliveryState.RELAYED)

        assertEquals(StepStatus.DONE, timeline[0].status)
        assertEquals(StepStatus.CURRENT, timeline[1].status)
        assertTrue(timeline[2].isNotYet, "relaying was presented as delivery")
        assertEquals(
            "Not yet — waiting for a phone with signal", timeline[2].detail,
        )
    }

    @Test
    fun `a delivered report marks the earlier steps done and the later ones not yet`() {
        val timeline = steps(DeliveryState.DELIVERED)

        assertEquals(listOf(StepStatus.DONE, StepStatus.DONE, StepStatus.CURRENT), timeline.take(3).map { it.status })
        assertTrue(timeline.drop(3).all { it.isNotYet })
    }

    @Test
    fun `a resolved report has every step behind it`() {
        val timeline = steps(DeliveryState.RESOLVED)

        assertTrue(timeline.none { it.isNotYet }, "a resolved report still shows a pending step")
        assertEquals(StepStatus.CURRENT, timeline.last().status)
    }

    /** The three responder states all sit on the same timeline step. */
    @Test
    fun `en route and on site both render as the assigned step`() {
        listOf(DeliveryState.ASSIGNED, DeliveryState.EN_ROUTE, DeliveryState.ON_SITE).forEach {
            assertEquals(
                StepStatus.CURRENT, steps(it)[3].status,
                "$it did not land on the assigned step",
            )
        }
    }

    @Test
    fun `there are always exactly five steps`() {
        DeliveryState.entries.forEach {
            assertEquals(5, steps(it).size, "$it produced the wrong number of steps")
        }
    }

    // --- the hop log ------------------------------------------------------

    private fun handoffs(vararg ids: String) =
        ids.mapIndexed { i, id -> Handoff(DeviceId(id), now + i * 120_000L) }

    @Test
    fun `the hop log numbers peers in the order this phone observed them`() {
        val log = steps(
            DeliveryState.RELAYED,
            handoffs("phone-7C4A", "phone-B119", "phone-2E80"),
        )[1].hopLog

        assertEquals(listOf(1, 2, 3), log.map { it.hop })
        assertEquals("phone-7C4A", log.first().peerPseudonym)
        assertEquals("phone-2E80", log.last().peerPseudonym)
    }

    @Test
    fun `handoffs recorded out of order are still numbered by time`() {
        val out = listOf(
            Handoff(DeviceId("late"), now + 500_000),
            Handoff(DeviceId("early"), now + 1_000),
        )

        val log = steps(DeliveryState.RELAYED, out)[1].hopLog

        assertEquals("early", log.first().peerPseudonym)
        assertEquals(1, log.first().hop)
    }

    @Test
    fun `the relay step names how many phones took a copy`() {
        assertEquals(
            "Relayed via 3 phones",
            steps(DeliveryState.RELAYED, handoffs("a", "b", "c"))[1].title,
        )
        assertEquals(
            "Relayed via 1 phone",
            steps(DeliveryState.RELAYED, handoffs("a"))[1].title,
        )
    }

    @Test
    fun `a report nobody has taken says so rather than showing an empty log`() {
        val step = steps(DeliveryState.SAVED_LOCAL)[1]

        assertTrue(step.hopLog.isEmpty())
        assertEquals("Not yet — no other phone has taken a copy", step.detail)
    }

    /** Only the relay step carries a hop log; a log elsewhere would be inventing evidence. */
    @Test
    fun `no step other than the relay step carries a hop log`() {
        val timeline = steps(DeliveryState.RESOLVED, handoffs("a", "b"))

        timeline.filter { it.ordinal != 2 }.forEach {
            assertTrue(it.hopLog.isEmpty(), "step ${it.ordinal} invented a hop log")
        }
    }

    // --- the affected strip ------------------------------------------------

    @Test
    fun `the affected strip reads as a sentence rather than a field dump`() {
        val summary = ReportDetailStateFactory.affectedSummary(
            report(
                DeliveryState.SAVED_LOCAL,
                payload = EmergencyPayload(
                    typeCode = "FLOOD",
                    affectedCount = 5,
                    childrenCount = 2,
                    elderlyCount = 1,
                    mobilityLimitedCount = 1,
                    isLifeThreatening = true,
                ),
            )
        )

        assertEquals(
            "5 people affected · 2 children · 1 elderly · 1 cannot walk alone · marked life-threatening",
            summary,
        )
    }

    @Test
    fun `zero counts are omitted rather than printed as zero`() {
        val summary = ReportDetailStateFactory.affectedSummary(
            report(
                DeliveryState.SAVED_LOCAL,
                payload = EmergencyPayload(typeCode = "FLOOD", affectedCount = 1),
            )
        )

        assertEquals("1 person affected", summary)
        assertTrue(!summary.contains("0"), "a zero count reached the screen")
    }

    /** The one item on the line that changes what anybody does about the report. */
    @Test
    fun `life-threatening is stated in words and never abbreviated`() {
        val summary = ReportDetailStateFactory.affectedSummary(
            report(
                DeliveryState.SAVED_LOCAL,
                payload = EmergencyPayload(
                    typeCode = "MEDICAL", affectedCount = 1, isLifeThreatening = true,
                ),
            )
        )

        assertTrue(summary.endsWith("marked life-threatening"))
    }

    // --- the header -------------------------------------------------------

    @Test
    fun `the header uses the bilingual label when one is known`() {
        val state = ReportDetailStateFactory.build(
            report(DeliveryState.SAVED_LOCAL),
            typeLabels = mapOf("FLOOD" to TypeLabel("FLOOD", "Flood", "Baha")),
        )

        assertEquals("Flood", state.typeLabelEn)
        assertEquals("Baha", state.typeLabelWar)
    }

    @Test
    fun `an unknown type falls back to its code rather than an empty header`() {
        val state = ReportDetailStateFactory.build(report(DeliveryState.SAVED_LOCAL))

        assertEquals("FLOOD", state.typeLabelEn)
    }

    @Test
    fun `a report the command center has not confirmed does not read as delivered`() {
        assertTrue(!ReportDetailStateFactory.build(report(DeliveryState.RELAYED)).isDelivered)
        assertTrue(ReportDetailStateFactory.build(report(DeliveryState.DELIVERED)).isDelivered)
    }
}
