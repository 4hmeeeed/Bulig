package ph.bulig.data.sync

import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

/**
 * The sync client, driven against a real HTTP server in the test JVM.
 *
 * MockWebServer rather than a hand-written fake: a fake `SyncApi` would only
 * prove the fake agrees with itself. This exercises real sockets, real status
 * codes, real timeouts and the actual bytes on the wire — which is where the
 * interesting failures live for an app whose defining condition is a bad
 * connection.
 */
class HttpSyncApiTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun stop() {
        server.shutdown()
    }

    private fun api(token: String? = "device-token-abc") = HttpSyncApi(
        config = SyncConfig(baseUrl = server.url("/").toString(), deviceToken = token),
        client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build(),
    )

    private fun request() = SyncRequestDto(
        clientClock = "2026-09-02T04:32:11Z",
        packets = listOf(
            PacketDto(
                packetId = "00000000-0000-4000-8000-000000000001",
                emergencyId = "11111111-0000-4000-8000-000000000001",
                originDeviceId = "22222222-0000-4000-8000-000000000001",
                hopCount = 2,
                ttlRemaining = 8,
                ttlInitial = 10,
                createdAtDevice = "2026-09-02T04:12:00Z",
                hmac = "f8c462f8b8f3d32fa09a8431202b448b",
                payload = PayloadDto(
                    typeCode = "FLOOD",
                    description = "water rising on the ground floor",
                    affectedCount = 4,
                    childrenCount = 2,
                    isLifeThreatening = true,
                    latitude = 11.2447,
                    longitude = 125.0048,
                ),
            )
        ),
    )

    private fun ok(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private val acceptedBody = """
        {
          "server_time": "2026-09-02T04:32:12Z",
          "clock_offset_ms": -1400,
          "sync_log_id": 91,
          "results": [
            {"packet_id":"00000000-0000-4000-8000-000000000001",
             "status":"ACCEPTED","emergency_code":"BLG-2026-0041","priority_level":"CRITICAL"}
          ],
          "summary": {"accepted": 1, "duplicate": 0, "rejected": 0}
        }
    """.trimIndent()

    // --- the happy path ---------------------------------------------------

    @Test
    fun `a successful push returns the server's verdict per packet`() {
        server.enqueue(ok(acceptedBody))

        val response = api().pushPackets(request())

        assertEquals(1, response.results.size)
        assertEquals("ACCEPTED", response.results.single().status)
        assertEquals("BLG-2026-0041", response.results.single().emergencyCode)
        assertEquals(-1400, response.clockOffsetMs)
    }

    @Test
    fun `the request goes to the contracted path and method`() {
        server.enqueue(ok(acceptedBody))
        api().pushPackets(request())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/sync/packets", recorded.path)
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test
    fun `the device token is sent as a bearer credential`() {
        server.enqueue(ok(acceptedBody))
        api().pushPackets(request())

        assertEquals("Bearer device-token-abc", server.takeRequest().getHeader("Authorization"))
    }

    /**
     * An unregistered phone must still be able to try. Registration is not a
     * prerequisite for reporting, so the absence of a token is a normal state
     * rather than something to assert against locally.
     */
    @Test
    fun `an unregistered device sends no authorization header rather than an empty one`() {
        server.enqueue(ok(acceptedBody))
        api(token = null).pushPackets(request())

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    /** The field names are a cross-language contract, so assert the actual bytes. */
    @Test
    fun `the body uses the snake_case names the Laravel validator expects`() {
        server.enqueue(ok(acceptedBody))
        api().pushPackets(request())

        val body = server.takeRequest().body.readUtf8()
        val sent = Json.parseToJsonElement(body).jsonObject

        assertTrue(sent.containsKey("client_clock"), "client_clock missing")
        assertTrue(sent.containsKey("packets"), "packets missing")

        listOf(
            "packet_id", "emergency_id", "origin_device_id", "hop_count",
            "ttl_remaining", "ttl_initial", "created_at_device",
            "type_code", "affected_count", "is_life_threatening",
        ).forEach {
            assertTrue(body.contains("\"$it\""), "wire name $it missing from the request body")
        }
    }

    @Test
    fun `the client clock is transmitted so the server can measure drift`() {
        server.enqueue(ok(acceptedBody))
        api().pushPackets(request())

        val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("2026-09-02T04:32:11Z", sent["client_clock"]!!.jsonPrimitive.content)
    }

    /**
     * The server is deployed separately and will gain fields this build has
     * never heard of. Failing on them would let one backend release stop every
     * phone in the barangay from syncing.
     */
    @Test
    fun `unknown response fields do not break a sync`() {
        server.enqueue(
            ok("""{"server_time":"2026-09-02T04:32:12Z","results":[],"a_field_from_the_future":42}""")
        )

        val response = api().pushPackets(request())
        assertEquals("2026-09-02T04:32:12Z", response.serverTime)
    }

    // --- failures the caller must distinguish ------------------------------

    @Test
    fun `a dead server is reported as unreachable and transient`() {
        server.shutdown()

        val failure = assertFailsWith<SyncException> { api().pushPackets(request()) }.failure

        assertTrue(failure is SyncFailure.Unreachable, "got $failure")
        assertTrue(failure.isTransient, "a dead server must be worth retrying")
    }

    @Test
    fun `a timeout is transient rather than a rejection`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val failure = assertFailsWith<SyncException> { api().pushPackets(request()) }.failure

        assertTrue(failure is SyncFailure.Unreachable, "got $failure")
        assertTrue(failure.isTransient)
    }

    @Test
    fun `a connection dropped mid-response is transient, not a success`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(acceptedBody)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )

        val failure = assertFailsWith<SyncException> { api().pushPackets(request()) }.failure
        assertTrue(failure.isTransient, "got $failure")
    }

    @Test
    fun `a server error is transient`() {
        server.enqueue(MockResponse().setResponseCode(503))

        val failure = assertFailsWith<SyncException> { api().pushPackets(request()) }.failure

        assertEquals(SyncFailure.ServerError(503), failure)
        assertTrue(failure.isTransient)
    }

    /** The server asking for room must be waited out, not treated as a refusal. */
    @Test
    fun `rate limiting is transient`() {
        server.enqueue(MockResponse().setResponseCode(429))

        val failure = assertFailsWith<SyncException> { api().pushPackets(request()) }.failure

        assertTrue(failure.isTransient, "429 must be retried after backoff, got $failure")
    }

    @Test
    fun `an expired token is reported as unauthorized rather than retried forever`() {
        server.enqueue(MockResponse().setResponseCode(401))

        val failure = assertFailsWith<SyncException> { api().pushPackets(request()) }.failure

        assertEquals(SyncFailure.Unauthorized, failure)
        assertTrue(!failure.isTransient, "retrying a dead token cannot help")
    }

    @Test
    fun `a validation rejection is permanent and keeps the server's reason`() {
        server.enqueue(
            MockResponse().setResponseCode(422).setBody("""{"message":"packets.0.hmac invalid"}""")
        )

        val failure = assertFailsWith<SyncException> { api().pushPackets(request()) }.failure

        assertTrue(failure is SyncFailure.Rejected, "got $failure")
        assertEquals(422, failure.status)
        assertTrue(failure.detail.contains("hmac"), "the server's reason was discarded")
        assertTrue(!failure.isTransient)
    }

    /**
     * The most dangerous case in this file. A 2xx that cannot be parsed must
     * never be read as "every packet delivered" — that would mark reports synced
     * that the server never stored.
     */
    @Test
    fun `an unparseable success is a failure, not a silent delivery`() {
        server.enqueue(ok("<html>502 Bad Gateway</html>"))

        val failure = assertFailsWith<SyncException> { api().pushPackets(request()) }.failure

        assertTrue(failure is SyncFailure.Unreadable, "got $failure")
    }

    @Test
    fun `a base url with a trailing slash does not produce a doubled path`() {
        server.enqueue(ok(acceptedBody))

        HttpSyncApi(
            SyncConfig(baseUrl = server.url("/").toString().trimEnd('/') + "/", deviceToken = null)
        ).pushPackets(request())

        assertEquals("/api/v1/sync/packets", server.takeRequest().path)
    }

    /**
     * Backoff belongs to SyncCoordinator. A retry loop hidden in here would
     * multiply against it and turn a 30-second backoff into a burst.
     */
    @Test
    fun `the client does not retry on its own`() {
        server.enqueue(MockResponse().setResponseCode(503))

        assertFailsWith<SyncException> { api().pushPackets(request()) }

        assertEquals(1, server.requestCount, "the client retried behind the coordinator's back")
    }
}
