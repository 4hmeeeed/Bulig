package ph.bulig.data.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ph.bulig.data.model.LocalReport
import ph.bulig.mesh.model.DeviceId
import ph.bulig.mesh.model.EmergencyId
import ph.bulig.mesh.model.EmergencyPayload
import ph.bulig.mesh.model.MeshPacket
import ph.bulig.mesh.model.PacketId

/** Artboard 09 — the screen the design calls the emotional core. */
class MeshStatusStateTest {

    private val now = 1_787_802_731_000L

    private fun carried(n: Int): List<LocalReport> = (1..n).map {
        LocalReport(
            packet = MeshPacket(
                packetId = PacketId("00000000-0000-4000-8000-%012d".format(it)),
                emergencyId = EmergencyId("11111111-0000-4000-8000-%012d".format(it)),
                originDeviceId = DeviceId("someone-else"),
                createdAtDeviceMs = now,
                payload = EmergencyPayload(typeCode = "FLOOD"),
            ),
            isMine = false,
        )
    }

    private fun state(
        carrying: Int = 2,
        peers: List<NearbyPeer> = emptyList(),
        active: Boolean = true,
        departed: DepartedPeer? = null,
    ) = MeshStatusStateFactory.build(
        carriedForOthers = carried(carrying),
        peers = peers,
        passedOnToday = 7,
        deliveredBecauseOfYou = 2,
        isRadioActive = active,
        departed = departed,
    )

    // --- the sentence the screen exists for --------------------------------

    /**
     * The design is explicit that this sentence, not a network graph, is the
     * point of the screen. It must read as something a person did for their
     * neighbours — not as a queue depth.
     */
    @Test
    fun `the headline tells the resident they are helping, in plain words`() {
        assertEquals(
            "You are carrying 2 reports for other people",
            state(carrying = 2).headline,
        )
    }

    @Test
    fun `one report is not described as one reports`() {
        assertEquals("You are carrying 1 report for someone else", state(carrying = 1).headline)
    }

    /** An idle phone is still a useful one, and the wording should not read as a failure. */
    @Test
    fun `carrying nothing reads as readiness rather than emptiness`() {
        val idle = state(carrying = 0)

        assertEquals("Your phone is ready to carry reports for other people", idle.headline)
        assertTrue(!idle.isHelping)
        assertTrue(idle.reassurance.contains("on its own"))
    }

    /**
     * The only place the app tells a resident to do nothing — which is the
     * correct instruction. Someone who thinks they must keep this screen open
     * will drain the battery the mesh depends on.
     */
    @Test
    fun `the reassurance tells the resident they need do nothing`() {
        assertTrue(state(carrying = 3).reassurance.contains("You do not have to do anything"))
    }

    @Test
    fun `a phone with its radio off says so instead of claiming to be helping`() {
        val off = state(carrying = 2, active = false)

        assertTrue(off.reassurance.contains("Bluetooth is off"))
        assertTrue(!off.isActive)
        assertTrue(
            !off.reassurance.contains("will hand them on"),
            "a phone that cannot relay promised to relay",
        )
    }

    // --- honesty about range and position ----------------------------------

    /** BLE range is short. The screen states a bound rather than implying coverage. */
    @Test
    fun `the range is stated rather than implied`() {
        assertEquals("within about 80 metres", state().rangeNote)
    }

    @Test
    fun `peers carry no position, only a rotating pseudonym`() {
        val peer = NearbyPeer("phone-7C4A", LinkQuality.STRONG, hopsFromSignal = 1)

        // The type has no latitude/longitude to leak: this asserts the shape of
        // what the screen can possibly show.
        assertEquals("phone-7C4A", peer.pseudonym)
        assertTrue(peer.description.isNotBlank())
    }

    @Test
    fun `the privacy note is always present`() {
        assertTrue(state().privacyNote.contains("random and change daily"))
        assertTrue(state().privacyNote.contains("no resident names"))
    }

    // --- nearby devices ----------------------------------------------------

    private val peers = listOf(
        NearbyPeer("phone-2E80", LinkQuality.WEAK, hopsFromSignal = null),
        NearbyPeer("phone-B119", LinkQuality.STRONG, hopsFromSignal = 2),
        NearbyPeer("phone-7C4A", LinkQuality.STRONG, hopsFromSignal = 1, hasInternet = true),
    )

    /**
     * A peer that can reach the server ends a packet's journey rather than
     * extending it — and this screen is also how a resident learns it is worth
     * walking towards someone.
     */
    @Test
    fun `a peer that can upload is listed first`() {
        val listed = state(peers = peers).peers

        assertEquals("phone-7C4A", listed.first().pseudonym)
        assertEquals(PeerRole.CAN_UPLOAD, listed.first().role)
    }

    @Test
    fun `remaining peers are ordered by how close they are to signal`() {
        val listed = state(peers = peers).peers.drop(1)

        assertEquals(listOf("phone-B119", "phone-2E80"), listed.map { it.pseudonym })
    }

    @Test
    fun `only a peer with internet claims it can upload`() {
        peers.filter { !it.hasInternet }.forEach {
            assertEquals(PeerRole.RELAYING, it.role, "${it.pseudonym} claimed it could upload")
        }
    }

    @Test
    fun `a peer that has not said its distance admits to not knowing`() {
        assertEquals(
            "weak link · hops unknown",
            NearbyPeer("phone-2E80", LinkQuality.WEAK, hopsFromSignal = null).description,
        )
    }

    @Test
    fun `hop distance reads naturally at one and at many`() {
        fun peer(h: Int) = NearbyPeer("p", LinkQuality.STRONG, hopsFromSignal = h).description

        assertTrue(peer(0).endsWith("has signal now"))
        assertTrue(peer(1).endsWith("1 hop from signal"))
        assertTrue(peer(3).endsWith("3 hops from signal"))
    }

    @Test
    fun `the nearby count matches the listed peers`() {
        val built = state(peers = peers)

        assertEquals(3, built.nearbyCount)
        assertEquals(3, built.peers.size)
        assertTrue(built.hasPeers)
    }

    @Test
    fun `a phone alone in range reports zero without inventing peers`() {
        val alone = state(peers = emptyList())

        assertEquals(0, alone.nearbyCount)
        assertTrue(!alone.hasPeers)
    }

    // --- churn -------------------------------------------------------------

    /** People walk out of range constantly. That is the design working, not failing. */
    @Test
    fun `a departed peer is reported as normal churn`() {
        val note = state(departed = DepartedPeer("phone-F03D", 40)).churnNote

        assertEquals("phone-F03D dropped out of range 40 s ago.", note)
    }

    @Test
    fun `no churn note is shown when nobody has left`() {
        assertNull(state().churnNote)
    }

    // --- contribution tiles -------------------------------------------------

    @Test
    fun `the contribution tiles carry the counts they were given`() {
        val built = state()

        assertEquals(7, built.passedOnToday)
        assertEquals(2, built.deliveredBecauseOfYou)
        assertEquals(2, built.carriedForOthers)
    }

    @Test
    fun `carrying reports for others is what counts as helping`() {
        assertTrue(state(carrying = 1).isHelping)
        assertTrue(!state(carrying = 0).isHelping)
    }
}
