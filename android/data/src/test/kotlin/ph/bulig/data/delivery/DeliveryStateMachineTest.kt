package ph.bulig.data.delivery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ph.bulig.data.model.LocalReport
import ph.bulig.data.store.InMemoryReportStore
import ph.bulig.mesh.delivery.DeliveryState
import ph.bulig.mesh.model.DeviceId
import ph.bulig.mesh.model.EmergencyId
import ph.bulig.mesh.model.EmergencyPayload
import ph.bulig.mesh.model.MeshPacket
import ph.bulig.mesh.model.PacketId

/**
 * Delivery state advances on evidence, and only forward.
 *
 * Reports arrive late and out of order over a mesh, so the question is not
 * whether stale evidence will show up — it is what happens when it does.
 */
class DeliveryStateMachineTest {

    private val store = InMemoryReportStore()
    private val machine = DeliveryStateMachine(store)

    private val packetId = PacketId("00000000-0000-4000-8000-000000000001")

    private fun seed(state: DeliveryState = DeliveryState.SAVED_LOCAL): LocalReport {
        val report = LocalReport(
            packet = MeshPacket(
                packetId = packetId,
                emergencyId = EmergencyId("11111111-0000-4000-8000-000000000001"),
                originDeviceId = DeviceId("this-device"),
                createdAtDeviceMs = 1_787_802_731_000,
                payload = EmergencyPayload(typeCode = "FLOOD"),
            ),
            deliveryState = state,
        )
        store.upsert(report)
        return report
    }

    @Test
    fun `a peer taking a copy advances a local report to relayed`() {
        seed()

        val updated = machine.apply(packetId, DeliveryEvidence.PeerTookCopy(handoffCount = 1))

        assertEquals(DeliveryState.RELAYED, updated!!.deliveryState)
        assertFalse(updated.synced, "relaying is not delivery")
    }

    @Test
    fun `server acknowledgement advances to delivered and records the code`() {
        seed(DeliveryState.RELAYED)

        val updated = machine.apply(
            packetId,
            DeliveryEvidence.ServerAcknowledged("BLG-2026-0417", "CRITICAL"),
        )!!

        assertEquals(DeliveryState.DELIVERED, updated.deliveryState)
        assertEquals("BLG-2026-0417", updated.emergencyCode)
        assertEquals("CRITICAL", updated.priorityLevel)
        assertTrue(updated.synced)
    }

    @Test
    fun `the full responder lifecycle advances in order`() {
        seed(DeliveryState.DELIVERED)

        assertEquals(
            DeliveryState.ASSIGNED,
            machine.apply(packetId, DeliveryEvidence.ResponderAssigned("Tanod R. Cinco"))!!.deliveryState,
        )
        assertEquals(
            DeliveryState.EN_ROUTE,
            machine.apply(packetId, DeliveryEvidence.ResponderEnRoute)!!.deliveryState,
        )
        assertEquals(
            DeliveryState.ON_SITE,
            machine.apply(packetId, DeliveryEvidence.ResponderOnSite)!!.deliveryState,
        )
        assertEquals(
            DeliveryState.RESOLVED,
            machine.apply(packetId, DeliveryEvidence.IncidentResolved)!!.deliveryState,
        )
    }

    /**
     * The failure this class exists to prevent: a finished emergency reopening
     * itself because a slow duplicate arrived from the mesh.
     */
    @Test
    fun `stale mesh evidence cannot reopen a resolved report`() {
        seed(DeliveryState.RESOLVED)

        val after = machine.apply(packetId, DeliveryEvidence.PeerTookCopy(handoffCount = 5))!!

        assertEquals(
            DeliveryState.RESOLVED, after.deliveryState,
            "a late relay event must not drag a resolved report backwards",
        )
    }

    @Test
    fun `a delivered report is not pulled back to relayed`() {
        seed(DeliveryState.DELIVERED)

        val after = machine.apply(packetId, DeliveryEvidence.PeerTookCopy(handoffCount = 2))!!

        assertEquals(DeliveryState.DELIVERED, after.deliveryState)
    }

    /**
     * A repeat acknowledgement cannot advance the state, but it still confirms
     * the packet is synced — a fact about transport, not a claim about progress.
     */
    @Test
    fun `a repeat acknowledgement still records transport facts`() {
        seed(DeliveryState.ASSIGNED)

        val after = machine.apply(
            packetId,
            DeliveryEvidence.ServerAcknowledged("BLG-2026-0417", "HIGH"),
        )!!

        assertEquals(DeliveryState.ASSIGNED, after.deliveryState, "state does not move backwards")
        assertTrue(after.synced, "but the packet is confirmed held by the server")
        assertEquals("BLG-2026-0417", after.emergencyCode)
    }

    /**
     * A permanently refused report is still only on this phone. Saying anything
     * else would be the exact lie the product forbids.
     */
    @Test
    fun `a permanent failure does not change what the resident is told`() {
        seed()

        val after = machine.markPermanentFailure(packetId, "Signature failed")!!

        assertEquals(DeliveryState.SAVED_LOCAL, after.deliveryState)
        assertEquals("Signature failed", after.permanentFailure)
        assertFalse(after.isPendingSync, "but it stops consuming retries")
    }

    @Test
    fun `evidence for an unknown packet is ignored safely`() {
        assertNull(machine.apply(packetId, DeliveryEvidence.ResponderOnSite))
        assertNull(machine.markPermanentFailure(packetId, "whatever"))
    }

    /** Evidence can skip states — a report may be delivered before we hear of any relay. */
    @Test
    fun `a report can jump straight from local to delivered`() {
        seed()

        val after = machine.apply(
            packetId,
            DeliveryEvidence.ServerAcknowledged("BLG-2026-0001", "LOW"),
        )!!

        assertEquals(DeliveryState.DELIVERED, after.deliveryState)
    }
}
