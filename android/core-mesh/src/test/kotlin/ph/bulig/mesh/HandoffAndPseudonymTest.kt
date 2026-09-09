package ph.bulig.mesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import ph.bulig.mesh.model.DeviceId
import ph.bulig.mesh.model.EmergencyId
import ph.bulig.mesh.model.EmergencyPayload
import ph.bulig.mesh.model.PeerPseudonym
import ph.bulig.mesh.model.PacketId

/**
 * What a device may honestly claim about a report's journey, and how peers are
 * named on screen without exposing anyone.
 */
class HandoffAndPseudonymTest {

    private val payload = EmergencyPayload(typeCode = "FLOOD", affectedCount = 5)
    private fun pid(n: Int) = PacketId("00000000-0000-4000-8000-%012d".format(n))
    private fun eid(n: Int) = EmergencyId("11111111-0000-4000-8000-%012d".format(n))

    @Test
    fun `a device records each handoff it performs with the time it observed`() {
        val mesh = VirtualMesh()
        listOf("A", "B", "C").forEach { mesh.addNode(it) }
        mesh.link("A", "B")
        mesh.link("A", "C")

        val start = mesh.nowMs()
        mesh.node("A").createEmergency(pid(1), eid(1), payload)
        mesh.runUntilQuiet()

        val handoffs = mesh.node("A").handoffsFor(pid(1))

        assertEquals(2, handoffs.size, "A gave a copy to both B and C")
        assertEquals(
            setOf(DeviceId("B"), DeviceId("C")),
            handoffs.map { it.peerId }.toSet(),
        )
        handoffs.forEach {
            assertTrue(it.atMs >= start, "handoff time must be observed, not invented")
        }
    }

    /**
     * The honesty boundary. A can see that B took a copy. A cannot see that B
     * later gave it to C — there is no back-channel through an offline mesh.
     */
    @Test
    fun `a device knows only its own handoffs never the full route`() {
        val mesh = VirtualMesh()
        listOf("A", "B", "C", "D").forEach { mesh.addNode(it) }
        mesh.chain("A", "B", "C", "D")

        mesh.node("A").createEmergency(pid(1), eid(1), payload)
        mesh.runUntilQuiet()

        // The packet genuinely travelled three hops.
        assertEquals(3, mesh.store("D").get(pid(1))!!.hopCount)

        // But its origin only ever witnessed the first one.
        val fromOrigin = mesh.node("A").handoffsFor(pid(1))
        assertEquals(1, fromOrigin.size, "A handed the report to exactly one peer")
        assertEquals(DeviceId("B"), fromOrigin.single().peerId)

        assertEquals(1, mesh.node("B").handoffCount(pid(1)), "B witnessed its own handoff to C")
        assertEquals(0, mesh.node("D").handoffCount(pid(1)), "D is the end of the line")
    }

    @Test
    fun `a phone can tell how many reports it carries for other people`() {
        val mesh = VirtualMesh()
        listOf("A", "B", "C").forEach { mesh.addNode(it) }
        mesh.link("A", "B")
        mesh.link("B", "C")

        mesh.node("A").createEmergency(pid(1), eid(1), payload)
        mesh.node("C").createEmergency(pid(2), eid(2), payload)
        mesh.node("B").createEmergency(pid(3), eid(3), payload)
        mesh.runUntilQuiet()

        val carried = mesh.node("B").carryingForOthers()

        assertEquals(2, carried.size, "B carries A's and C's reports, not its own")
        assertTrue(carried.none { it.originDeviceId == DeviceId("B") })
    }

    // --- pseudonyms -------------------------------------------------------

    @Test
    fun `a pseudonym is stable within a day and changes the next day`() {
        val device = DeviceId("1f2e3d4c-5b6a-4798-8877-000000000003")

        val monday = PeerPseudonym.forDevice(device, dayEpoch = 20_000)
        val mondayAgain = PeerPseudonym.forDevice(device, dayEpoch = 20_000)
        val tuesday = PeerPseudonym.forDevice(device, dayEpoch = 20_001)

        assertEquals(monday, mondayAgain, "a peer must not be renamed mid-conversation")
        assertNotEquals(
            monday, tuesday,
            "a device that keeps one name forever can be tracked across days",
        )
    }

    @Test
    fun `pseudonyms match the phone-XXXX format used on screen`() {
        val name = PeerPseudonym.forDevice(DeviceId("abc-123"), dayEpoch = 20_000)

        assertTrue(
            Regex("^phone-[0-9A-F]{4}$").matches(name),
            "unexpected pseudonym format: $name",
        )
    }

    /** The pseudonym must not leak the identity it stands in for. */
    @Test
    fun `a pseudonym reveals nothing about the underlying device id`() {
        val device = DeviceId("1f2e3d4c-5b6a-4798-8877-000000000003")
        val name = PeerPseudonym.forDevice(device, dayEpoch = 20_000)

        val suffix = name.removePrefix("phone-")
        assertFalse(
            device.value.contains(suffix, ignoreCase = true),
            "the displayed name is a substring of the real device id",
        )
    }

    @Test
    fun `different devices get different names on the same day`() {
        val names = (1..200)
            .map { PeerPseudonym.forDevice(DeviceId("device-$it"), dayEpoch = 20_000) }

        // A few collisions are tolerable in a 16-bit space; wholesale collapse is not.
        assertTrue(
            names.distinct().size > 190,
            "too many devices share a name: ${names.distinct().size} distinct of 200",
        )
    }

    @Test
    fun `the day epoch advances once per day`() {
        val noon = 1_787_802_731_000L

        assertEquals(
            PeerPseudonym.dayEpochOf(noon),
            PeerPseudonym.dayEpochOf(noon + 3_600_000),
            "an hour later is the same day",
        )
        assertEquals(
            PeerPseudonym.dayEpochOf(noon) + 1,
            PeerPseudonym.dayEpochOf(noon + 86_400_000),
        )
    }
}
