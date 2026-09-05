package ph.bulig.data.auth

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import ph.bulig.data.presentation.Assignment
import ph.bulig.data.presentation.ResponderStatus
import ph.bulig.data.sync.SyncConfig

class AssignmentActionsTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun stop() = server.shutdown()

    private fun actions() = AssignmentActions(SyncConfig(server.url("/").toString()))

    private fun assignment(id: Long? = 7) = Assignment(
        assignmentId = id,
        emergencyCode = "BLG-2026-0041",
        typeCode = "FLOOD",
        filedAtMs = 1_787_802_731_000L,
        receivedAtMs = 1_787_802_731_000L,
    )

    private fun ok() = MockResponse().setResponseCode(200).setBody("""{"assignment":{}}""")

    // --- the right endpoint for each change ---------------------------------

    /**
     * Accept and decline are their own routes because they are decisions with
     * consequences beyond a status column — declining returns the incident to
     * the operator's queue and frees the responder.
     */
    @Test
    fun `accepting goes to the accept endpoint`() {
        server.enqueue(ok())

        val outcome = actions().push("tok", assignment(), ResponderStatus.ACCEPTED)

        assertTrue(outcome.isUploaded)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/v1/assignments/7/accept", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))
    }

    @Test
    fun `declining sends its reason`() {
        server.enqueue(ok())

        val outcome = actions().push(
            "tok", assignment(), ResponderStatus.DECLINED, declineReason = "Road impassable",
        )

        assertTrue(outcome.isUploaded)
        val request = server.takeRequest()
        assertEquals("/api/v1/assignments/7/decline", request.path)
        assertTrue(request.body.readUtf8().contains("Road impassable"))
    }

    /**
     * An assignment bouncing back to the queue with no explanation tells an
     * operator nothing about whether to send somebody else — which is why the
     * server requires a reason and why this refuses without one rather than
     * sending a request it knows will fail.
     */
    @Test
    fun `declining without a reason is refused before it reaches the network`() {
        val outcome = actions().push("tok", assignment(), ResponderStatus.DECLINED)

        assertTrue(outcome is PushOutcome.Refused, "got $outcome")
        assertEquals(0, server.requestCount, "a request the server would reject was still sent")
    }

    @Test
    fun `progress along a taken job goes to the status endpoint`() {
        listOf(
            ResponderStatus.EN_ROUTE,
            ResponderStatus.ON_SITE,
            ResponderStatus.RESOLVED,
        ).forEach { status ->
            server.enqueue(ok())

            assertTrue(actions().push("tok", assignment(), status).isUploaded)

            val request = server.takeRequest()
            assertEquals("/api/v1/assignments/7/status", request.path)
            assertTrue(
                request.body.readUtf8().contains("\"${status.name}\""),
                "$status was not the status sent",
            )
        }
    }

    /** The server validates exactly EN_ROUTE, ON_SITE and RESOLVED. */
    @Test
    fun `assigned is not a reportable change`() {
        val outcome = actions().push("tok", assignment(), ResponderStatus.ASSIGNED)

        assertTrue(outcome is PushOutcome.Refused, "got $outcome")
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an assignment with no server id cannot be pushed`() {
        val outcome = actions().push("tok", assignment(id = null), ResponderStatus.ACCEPTED)

        assertTrue(outcome is PushOutcome.Refused, "got $outcome")
        assertEquals(0, server.requestCount)
    }

    // --- failing without losing the responder's work -------------------------

    /**
     * The rule this class exists to serve: a responder standing in floodwater
     * has already made the change locally. Nothing here can undo it, and every
     * failure only decides what the pill says.
     */
    @Test
    fun `no signal defers rather than throwing`() {
        server.shutdown()

        val outcome = actions().push("tok", assignment(), ResponderStatus.ON_SITE)

        assertTrue(outcome is PushOutcome.Deferred, "got $outcome")
        assertTrue(!outcome.isUploaded)
    }

    @Test
    fun `a server error defers so it can be retried`() {
        server.enqueue(MockResponse().setResponseCode(503))

        assertTrue(actions().push("tok", assignment(), ResponderStatus.ON_SITE) is PushOutcome.Deferred)
    }

    @Test
    fun `rate limiting defers`() {
        server.enqueue(MockResponse().setResponseCode(429))

        assertTrue(actions().push("tok", assignment(), ResponderStatus.ON_SITE) is PushOutcome.Deferred)
    }

    /**
     * A 409 means an operator moved this assignment while the responder was
     * walking. Retrying cannot help — they need to look at the queue again.
     */
    @Test
    fun `a conflict tells the responder the command center changed it`() {
        server.enqueue(MockResponse().setResponseCode(409))

        val outcome = actions().push("tok", assignment(), ResponderStatus.RESOLVED)

        assertTrue(outcome is PushOutcome.Refused, "got $outcome")
        assertTrue(outcome.reason.contains("command center"))
    }

    @Test
    fun `an expired sign-in is refused rather than retried forever`() {
        server.enqueue(MockResponse().setResponseCode(401))

        val outcome = actions().push("tok", assignment(), ResponderStatus.EN_ROUTE)

        assertTrue(outcome is PushOutcome.Refused, "got $outcome")
        assertTrue(outcome.reason.contains("sign-in"))
    }

    @Test
    fun `a validation rejection is permanent`() {
        server.enqueue(MockResponse().setResponseCode(422))

        assertTrue(actions().push("tok", assignment(), ResponderStatus.ON_SITE) is PushOutcome.Refused)
    }

    @Test
    fun `accept sends a body the server can parse even though it needs none`() {
        server.enqueue(ok())
        actions().push("tok", assignment(), ResponderStatus.ACCEPTED)

        // An empty PATCH body makes some servers 400 on content-type parsing.
        assertEquals("{}", server.takeRequest().body.readUtf8())
    }
}
