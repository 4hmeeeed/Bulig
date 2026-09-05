package ph.bulig.data.auth

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import ph.bulig.data.presentation.AssignmentListStateFactory
import ph.bulig.data.presentation.ResponderStatus
import ph.bulig.data.sync.SyncConfig
import ph.bulig.mesh.priority.PriorityLevel

class AssignmentApiTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun stop() = server.shutdown()

    private fun api() = AssignmentApi(SyncConfig(server.url("/").toString()))

    private fun body(vararg assignments: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"assignments":[${assignments.joinToString(",")}]}""")

    private fun assignment(
        id: Long = 1,
        status: String = "assigned",
        priority: String = "CRITICAL",
        filed: String = "2026-09-02T04:12:00Z",
        received: String = "2026-09-02T04:32:00Z",
        hops: Int = 3,
    ) = """
        {"id":$id,"status":"$status","assigned_at":"2026-09-02T04:33:00Z",
         "emergency":{"emergency_code":"BLG-2026-004$id","priority_level":"$priority",
           "description":"Taas na an tubig","affected_count":5,"children_count":2,
           "elderly_count":1,"mobility_limited_count":1,"is_life_threatening":true,
           "created_at_device":"$filed","received_at_server":"$received","hop_count":$hops,
           "type":{"code":"FLOOD","label_en":"Flood"},
           "location":{"latitude":11.2447,"longitude":125.0048,"accuracy_m":38.0,"purok":"Purok 4"}}}
    """.trimIndent().replace("\n", "")

    // --- fetching ---------------------------------------------------------

    @Test
    fun `a responder's queue is fetched and mapped`() {
        server.enqueue(body(assignment()))

        val queue = api().mine("tok")

        assertEquals(1, queue.size)
        assertEquals("BLG-2026-0041", queue.single().emergencyCode)
        assertEquals("FLOOD", queue.single().typeCode)
        assertEquals(PriorityLevel.CRITICAL, queue.single().priorityLevel)
        assertEquals(5, queue.single().affectedCount)
    }

    @Test
    fun `the request is authenticated as a person and asks for active work`() {
        server.enqueue(body())
        api().mine("tok")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/me/assignments?active=1", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))
    }

    @Test
    fun `an empty queue is a normal answer`() {
        server.enqueue(body())

        assertTrue(api().mine("tok").isEmpty())
    }

    /**
     * The distinction the whole responder model is built around: a report that
     * crossed three phones reached the responder twenty minutes after it was
     * written, and collapsing the two timestamps would make it look fresh.
     */
    @Test
    fun `filing time and receipt time are kept distinct`() {
        server.enqueue(
            body(
                assignment(
                    filed = "2026-09-02T04:12:00Z",
                    received = "2026-09-02T04:32:00Z",
                )
            )
        )

        val a = api().mine("tok").single()

        assertEquals(20 * 60_000L, a.meshLatencyMs)
        assertTrue(a.arrivedByMesh)
    }

    /** And the queue's age is measured from the earlier of the two. */
    @Test
    fun `the queue ages a mesh-delayed report from when it was filed`() {
        server.enqueue(body(assignment(filed = "2026-09-02T04:12:00Z", received = "2026-09-02T04:32:00Z")))

        val now = java.time.Instant.parse("2026-09-02T04:34:00Z").toEpochMilli()
        val state = AssignmentListStateFactory.build(
            responderName = "R", zone = null, assignments = api().mine("tok"), nowMs = now,
        )

        assertEquals(
            "22 min ago", state.rows.single().ageLabel,
            "a report that took 20 minutes to arrive was shown as 2 minutes old",
        )
    }

    @Test
    fun `location and hop count reach the card`() {
        server.enqueue(body(assignment(hops = 2)))

        val a = api().mine("tok").single()

        assertEquals("Purok 4", a.purok)
        assertEquals(38, a.accuracyM)
        assertEquals(2, a.hopCount)
    }

    // --- degrading safely --------------------------------------------------

    /**
     * An assignment with no emergency cannot be acted on, and an empty card
     * would waste a responder's attention when it is scarcest.
     */
    @Test
    fun `an assignment with no emergency is dropped rather than shown empty`() {
        server.enqueue(body("""{"id":9,"status":"assigned"}""", assignment(id = 1)))

        val queue = api().mine("tok")

        assertEquals(1, queue.size)
        assertEquals("BLG-2026-0041", queue.single().emergencyCode)
    }

    @Test
    fun `an unrecognised status is treated as newly assigned`() {
        assertEquals(ResponderStatus.ASSIGNED, responderStatus("teleported"))
        assertEquals(ResponderStatus.ASSIGNED, responderStatus(null))
        assertEquals(ResponderStatus.EN_ROUTE, responderStatus("en_route"))
        assertEquals(ResponderStatus.ON_SITE, responderStatus("ON_SITE"))
    }

    /**
     * The middle band on purpose: guessing CRITICAL would push a real emergency
     * down the queue behind an unknown, and guessing LOW would bury the unknown.
     */
    @Test
    fun `an unrecognised priority lands in the middle rather than at either end`() {
        assertEquals(PriorityLevel.MODERATE, priorityLevel("EXTREME"))
        assertEquals(PriorityLevel.MODERATE, priorityLevel(null))
        assertEquals(PriorityLevel.CRITICAL, priorityLevel("critical"))
    }

    @Test
    fun `a missing timestamp does not drop the rescue`() {
        server.enqueue(
            body(
                """{"id":1,"status":"assigned","emergency":{"emergency_code":"BLG-1",
                   "type":{"code":"FIRE"},"affected_count":2}}""".replace("\n", "")
            )
        )

        val a = api().mine("tok").single()

        assertEquals("BLG-1", a.emergencyCode)
        assertEquals("FIRE", a.typeCode)
    }

    @Test
    fun `timestamps without a zone designator still parse`() {
        assertEquals(
            java.time.Instant.parse("2026-09-02T04:12:00Z").toEpochMilli(),
            parseIso("2026-09-02 04:12:00"),
        )
        assertNull(parseIso("not a date"))
        assertNull(parseIso(null))
    }

    // --- failures ----------------------------------------------------------

    @Test
    fun `an expired token is reported as a credential problem`() {
        server.enqueue(MockResponse().setResponseCode(401))

        val failure = assertFailsWith<LoginException> { api().mine("tok") }.failure

        assertEquals(LoginFailure.WrongCredentials, failure)
    }

    @Test
    fun `an unreachable server is transient`() {
        server.shutdown()

        val failure = assertFailsWith<LoginException> { api().mine("tok") }.failure

        assertTrue(failure.isTransient, "got $failure")
    }

    @Test
    fun `an unparseable body is a failure rather than an empty queue`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>oops</html>"))

        assertFailsWith<LoginException> { api().mine("tok") }
    }
}
