package ph.bulig.data.sync

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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class DeviceRegistrarTest {

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

    private fun registrar() =
        DeviceRegistrar(SyncConfig(baseUrl = server.url("/").toString()))

    private fun request() = RegisterRequestDto(
        deviceId = "22222222-0000-4000-8000-000000000001",
        model = "Redmi 9A",
        androidVersion = "12",
        supportsAdvertising = true,
    )

    /** 32 bytes, as the server's `random_bytes(32)` produces. */
    private val validKeyHex = "a3".repeat(32)

    private fun okBody(keyHex: String = validKeyHex) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {
              "device_token": "17|abcdefghijklmnop",
              "hmac_key": "$keyHex",
              "server_time": "2026-09-02T04:32:12Z",
              "ttl_initial": 10
            }
            """.trimIndent()
        )

    @Test
    fun `registration returns a token and a usable signing key`() {
        server.enqueue(okBody())

        val credentials = registrar().register(request())

        assertEquals("17|abcdefghijklmnop", credentials.token)
        assertEquals(32, credentials.hmacKey.size)
        assertEquals(10, credentials.ttlInitial)
    }

    @Test
    fun `the request goes to the contracted endpoint with snake_case fields`() {
        server.enqueue(okBody())
        registrar().register(request())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/devices/register", recorded.path)

        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"device_id\""))
        assertTrue(body.contains("\"android_version\""))
        assertTrue(body.contains("\"supports_advertising\""))
    }

    @Test
    fun `registration sends no credential because it is how one is obtained`() {
        server.enqueue(okBody())
        registrar().register(request())

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `the device id the caller minted is the one carried on the credentials`() {
        server.enqueue(okBody())

        val credentials = registrar().register(request())

        assertEquals(request().deviceId, credentials.deviceId)

        val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(request().deviceId, sent["device_id"]!!.jsonPrimitive.content)
    }

    // --- the key ----------------------------------------------------------

    @Test
    fun `the hex key decodes to the exact bytes the server issued`() {
        server.enqueue(okBody(keyHex = "00ff10" + "ab".repeat(29)))

        val key = registrar().register(request()).hmacKey

        assertEquals(0x00.toByte(), key[0])
        assertEquals(0xff.toByte(), key[1])
        assertEquals(0x10.toByte(), key[2])
    }

    /**
     * A short key would silently weaken every signature this device ever
     * produces, and nothing downstream would notice — the packets would still
     * be signed, just badly.
     */
    @Test
    fun `a key shorter than the server should ever issue is refused`() {
        server.enqueue(okBody(keyHex = "ab".repeat(8)))

        val failure = assertFailsWith<SyncException> { registrar().register(request()) }.failure

        assertTrue(failure is SyncFailure.Unreadable, "got $failure")
        assertTrue(failure.detail.contains("8"), "the failure should name the size it got")
    }

    @Test
    fun `a key that is not hex is refused rather than half decoded`() {
        server.enqueue(okBody(keyHex = "zzzz" + "ab".repeat(30)))

        val failure = assertFailsWith<SyncException> { registrar().register(request()) }.failure
        assertTrue(failure is SyncFailure.Unreadable, "got $failure")
    }

    @Test
    fun `hex decoding rejects odd length and empty input`() {
        assertNull(DeviceRegistrar.decodeHex("abc"))
        assertNull(DeviceRegistrar.decodeHex(""))
        assertEquals(2, DeviceRegistrar.decodeHex("00ff")!!.size)
    }

    // --- failures ---------------------------------------------------------

    /** Revocation is a decision an operator made. Retrying cannot undo it. */
    @Test
    fun `a revoked device is refused permanently`() {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"message":"Device is revoked.","code":"DEVICE_REVOKED"}""")
        )

        val failure = assertFailsWith<SyncException> { registrar().register(request()) }.failure

        assertTrue(failure is SyncFailure.Rejected, "got $failure")
        assertTrue(!failure.isTransient, "a revoked device must stop asking")
    }

    @Test
    fun `a server error during registration is transient`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val failure = assertFailsWith<SyncException> { registrar().register(request()) }.failure

        assertTrue(failure.isTransient, "got $failure")
    }

    @Test
    fun `an unreachable server is transient`() {
        server.shutdown()

        val failure = assertFailsWith<SyncException> { registrar().register(request()) }.failure

        assertTrue(failure is SyncFailure.Unreachable, "got $failure")
        assertTrue(failure.isTransient)
    }

    // --- credentials as a value -------------------------------------------

    /**
     * The default data-class `toString` would print the signing key into any
     * crash report or log line that touched it.
     */
    @Test
    fun `credentials never print their secrets`() {
        server.enqueue(okBody())
        val text = registrar().register(request()).toString()

        assertTrue(!text.contains(validKeyHex), "the signing key was printed")
        assertTrue(!text.contains("abcdefghijklmnop"), "the token was printed")
        assertTrue(text.contains("22222222"), "the device id should still be identifiable")
    }

    @Test
    fun `two identical credentials compare equal despite holding byte arrays`() {
        val a = DeviceCredentials("d", "t", byteArrayOf(1, 2, 3), 10)
        val b = DeviceCredentials("d", "t", byteArrayOf(1, 2, 3), 10)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
