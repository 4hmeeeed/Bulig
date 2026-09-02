package ph.bulig.data.sync

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import ph.bulig.data.delivery.DeliveryStateMachine
import ph.bulig.data.model.LocalReport
import ph.bulig.data.store.InMemoryReportStore
import ph.bulig.mesh.Clock
import ph.bulig.mesh.delivery.DeliveryState
import ph.bulig.mesh.model.DeviceId
import ph.bulig.mesh.model.EmergencyId
import ph.bulig.mesh.model.EmergencyPayload
import ph.bulig.mesh.model.MeshPacket
import ph.bulig.mesh.model.PacketId

/**
 * The whole upload path, from stored report to real HTTP and back.
 *
 * [SyncCoordinator] was previously only ever driven by a fake `SyncApi`, which
 * proves the coordinator agrees with the fake. This drives it through
 * [HttpSyncApi] against a real server, so the bytes, the status codes and the
 * delivery-state transitions are all exercised at once — the seam where two
 * separately-tested halves usually turn out to disagree.
 */
class SyncPathEndToEndTest {

    private val now = 1_787_802_731_000L

    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryReportStore

    @BeforeTest
    fun start() {
        server = MockWebServer()
        server.start()
        store = InMemoryReportStore()
    }

    @AfterTest
    fun stop() {
        server.shutdown()
    }

    private fun coordinator(online: Boolean = true) = SyncCoordinator(
        store = store,
        api = HttpSyncApi(SyncConfig(server.url("/").toString(), deviceToken = "tok")),
        connectivity = { online },
        stateMachine = DeliveryStateMachine(store),
        clock = Clock { now },
    )

    private fun report(n: Int, lifeThreatening: Boolean = false): LocalReport {
        val packet = MeshPacket(
            packetId = PacketId("00000000-0000-4000-8000-%012d".format(n)),
            emergencyId = EmergencyId("11111111-0000-4000-8000-%012d".format(n)),
            originDeviceId = DeviceId("22222222-0000-4000-8000-000000000001"),
            createdAtDeviceMs = now - n * 60_000L,
            payload = EmergencyPayload(
                typeCode = "FLOOD",
                affectedCount = 3,
                isLifeThreatening = lifeThreatening,
            ),
        )
        return LocalReport(packet = packet, isMine = true).also { store.upsert(it) }
    }

    private fun jsonResponse(vararg results: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {"server_time":"2026-09-02T04:32:12Z","clock_offset_ms":-1400,
             "results":[${results.joinToString(",")}]}
            """.trimIndent()
        )

    private fun accepted(n: Int, code: String) =
        """{"packet_id":"00000000-0000-4000-8000-%012d",
            "status":"ACCEPTED","emergency_code":"$code","priority_level":"HIGH"}"""
            .format(n).replace("\n", "")

    // --- the path works ---------------------------------------------------

    /**
     * The moment the whole offline-first design is aiming at: a report written
     * with no connectivity finally reaches the server, and only then is the
     * resident shown a confirmed delivery.
     */
    @Test
    fun `a queued report reaches the server and is marked delivered`() {
        val queued = report(1)
        assertEquals(DeliveryState.SAVED_LOCAL, queued.deliveryState)

        server.enqueue(jsonResponse(accepted(1, "BLG-2026-0041")))

        val outcome = coordinator().syncOnce(now)

        assertEquals(1, outcome.accepted)
        assertTrue(!outcome.failed)

        val stored = store.get(queued.packetId)!!
        assertTrue(stored.synced, "the report is still owed to the server")
        assertEquals("BLG-2026-0041", stored.emergencyCode)
        assertTrue(
            stored.deliveryState.isConfirmedByCommandCenter,
            "delivery was not confirmed after the server accepted it",
        )
    }

    @Test
    fun `the server assigned code and priority are written back to the report`() {
        report(1)
        server.enqueue(jsonResponse(accepted(1, "BLG-2026-0099")))

        coordinator().syncOnce(now)

        val stored = store.all().single()
        assertEquals("BLG-2026-0099", stored.emergencyCode)
        assertEquals("HIGH", stored.priorityLevel)
    }

    /**
     * The mesh delivers the same packet by several routes on purpose, so the
     * server is guaranteed to see duplicates. A duplicate means the server holds
     * it, which is the outcome that mattered.
     */
    @Test
    fun `a duplicate counts as delivered and stops being retried`() {
        report(1)
        server.enqueue(
            jsonResponse(
                """{"packet_id":"00000000-0000-4000-8000-000000000001","status":"DUPLICATE"}"""
            )
        )

        val outcome = coordinator().syncOnce(now)

        assertEquals(1, outcome.duplicate)
        assertTrue(store.all().single().synced)
        assertEquals(0, store.pendingSync().size)
    }

    @Test
    fun `a whole batch goes in one request`() {
        report(1); report(2); report(3)

        server.enqueue(
            jsonResponse(
                accepted(1, "BLG-1"), accepted(2, "BLG-2"), accepted(3, "BLG-3")
            )
        )

        val outcome = coordinator().syncOnce(now)

        assertEquals(3, outcome.accepted)
        assertEquals(1, server.requestCount, "one connectivity window, one request")
    }

    /** A connectivity window may be seconds long. What is first is what survives it. */
    @Test
    fun `life-threatening reports are sent ahead of older ordinary ones`() {
        report(9)                              // oldest
        report(1, lifeThreatening = true)      // newest, but urgent

        server.enqueue(jsonResponse(accepted(1, "BLG-1"), accepted(9, "BLG-9")))

        coordinator().syncOnce(now)

        val body = server.takeRequest().body.readUtf8()
        val urgentAt = body.indexOf("000000000001")
        val ordinaryAt = body.indexOf("000000000009")

        assertTrue(urgentAt in 0 until ordinaryAt, "the urgent report was not sent first")
    }

    // --- the path fails safely --------------------------------------------

    /**
     * The failure that must never be silent. If a transport error marked reports
     * delivered, a resident would be told help is coming when the server has
     * never heard of them.
     */
    @Test
    fun `an unreachable server leaves every report queued and unconfirmed`() {
        report(1); report(2)
        server.shutdown()

        val outcome = coordinator().syncOnce(now)

        assertTrue(outcome.failed)
        assertEquals(0, outcome.accepted)
        assertEquals(2, store.pendingSync().size, "reports were dropped from the queue")
        store.all().forEach {
            assertTrue(!it.synced)
            assertTrue(
                !it.deliveryState.isConfirmedByCommandCenter,
                "a failed upload was presented as a confirmed delivery",
            )
        }
    }

    @Test
    fun `a server error leaves the queue intact for the next window`() {
        report(1)
        server.enqueue(MockResponse().setResponseCode(503))

        val outcome = coordinator().syncOnce(now)

        assertTrue(outcome.failed)
        assertEquals(1, store.pendingSync().size)
    }

    /** A 2xx that cannot be parsed must not be read as "everything delivered". */
    @Test
    fun `an unparseable success does not mark anything delivered`() {
        report(1)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("<html>502 Bad Gateway</html>")
        )

        val outcome = coordinator().syncOnce(now)

        assertTrue(outcome.failed)
        assertTrue(!store.all().single().synced)
    }

    @Test
    fun `an offline device does not open a socket at all`() {
        report(1)

        val outcome = coordinator(online = false).syncOnce(now)

        assertTrue(outcome.failed)
        assertEquals("offline", outcome.error)
        assertEquals(0, server.requestCount, "an offline device tried to reach the network")
    }

    @Test
    fun `nothing to send means no request`() {
        val outcome = coordinator().syncOnce(now)

        assertEquals(0, outcome.attempted)
        assertTrue(!outcome.failed)
        assertEquals(0, server.requestCount)
    }

    /**
     * A rejected packet must stop being retried. A phone resending one every
     * thirty seconds for a week is a battery drain a barangay cannot afford.
     */
    @Test
    fun `a permanently rejected packet leaves the queue`() {
        report(1)
        server.enqueue(
            jsonResponse(
                """{"packet_id":"00000000-0000-4000-8000-000000000001",
                    "status":"INVALID_HMAC","reason":"signature mismatch"}""".replace("\n", "")
            )
        )

        coordinator().syncOnce(now)

        assertEquals(0, store.pendingSync().size, "a hopeless packet is still being retried")
        assertTrue(!store.all().single().synced, "a rejected packet must not read as delivered")
    }
}
