package ph.bulig.data.registration

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import ph.bulig.data.store.InMemoryCredentialStore
import ph.bulig.data.store.InMemoryDeviceIdentityStore
import ph.bulig.data.sync.DeviceRegistrar
import ph.bulig.data.sync.SyncConfig

class RegistrationManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var credentials: InMemoryCredentialStore
    private lateinit var identity: InMemoryDeviceIdentityStore
    private var minted = 0

    @BeforeTest
    fun start() {
        server = MockWebServer()
        server.start()
        credentials = InMemoryCredentialStore()
        identity = InMemoryDeviceIdentityStore()
        minted = 0
    }

    @AfterTest
    fun stop() = server.shutdown()

    private fun manager() = RegistrationManager(
        registrar = DeviceRegistrar(SyncConfig(server.url("/").toString())),
        credentials = credentials,
        identity = identity,
        newUuid = { "device-${++minted}" },
        deviceModel = "Redmi 9A",
        androidVersion = "12",
    )

    private fun ok() = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"device_token":"17|tok","hmac_key":"${"a3".repeat(32)}",
                "server_time":"2026-09-02T04:32:12Z","ttl_initial":10}""".replace("\n", "")
        )

    // --- device identity ---------------------------------------------------

    /**
     * The identity a packet is signed under. Regenerating it per launch would
     * orphan every report already travelling through the mesh.
     */
    @Test
    fun `the device id is minted once and kept`() {
        val m = manager()

        val first = m.deviceId()
        assertEquals(first, m.deviceId())
        assertEquals(first, manager().deviceId(), "a new instance minted a second identity")
        assertEquals(1, minted)
    }

    @Test
    fun `an existing device id is reused rather than replaced`() {
        identity.saveDeviceId("existing-device")

        assertEquals("existing-device", manager().deviceId())
        assertEquals(0, minted, "an identity was minted over an existing one")
    }

    /**
     * A report filed on a phone that has never had signal still has to say which
     * device it came from, so the id must exist long before registration does.
     */
    @Test
    fun `a device id exists before any registration succeeds`() {
        val m = manager()

        assertTrue(m.deviceId().isNotBlank())
        assertTrue(!m.isRegistered())
        assertNull(m.signingKey())
    }

    // --- registering -------------------------------------------------------

    @Test
    fun `a successful registration stores the token and key`() {
        server.enqueue(ok())
        val m = manager()

        val outcome = m.ensureRegistered()

        assertTrue(outcome is RegistrationOutcome.Registered, "got $outcome")
        assertTrue(m.isRegistered())
        assertEquals("17|tok", m.deviceToken())
        assertEquals(32, m.signingKey()!!.size)
    }

    @Test
    fun `the registration is sent under the device's own id`() {
        server.enqueue(ok())
        val m = manager()
        val id = m.deviceId()

        m.ensureRegistered()

        assertTrue(server.takeRequest().body.readUtf8().contains(id))
    }

    /**
     * Re-registering rotates the key server-side and invalidates prior tokens,
     * which is how a lost phone is cut off. Doing it on every launch would cut
     * the phone off from itself.
     */
    @Test
    fun `an already-registered device does not ask again`() {
        server.enqueue(ok())
        val m = manager()
        m.ensureRegistered()

        val second = m.ensureRegistered()

        assertTrue(second is RegistrationOutcome.AlreadyRegistered, "got $second")
        assertEquals(1, server.requestCount, "a registered device re-registered itself")
    }

    // --- failing safely ----------------------------------------------------

    /**
     * The rule the whole architecture rests on: failing to register never stops
     * a resident filing a report. Every failure below must be non-fatal.
     */
    @Test
    fun `an unreachable server defers rather than throwing`() {
        server.shutdown()

        val outcome = manager().ensureRegistered()

        assertTrue(outcome is RegistrationOutcome.Deferred, "got $outcome")
    }

    @Test
    fun `a server error defers so the next opportunity retries`() {
        server.enqueue(MockResponse().setResponseCode(500))

        assertTrue(manager().ensureRegistered() is RegistrationOutcome.Deferred)
    }

    /** An operator's decision. Retrying cannot undo it, so the device stops asking. */
    @Test
    fun `a revoked device is refused permanently`() {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"message":"Device is revoked.","code":"DEVICE_REVOKED"}""")
        )

        val outcome = manager().ensureRegistered()

        assertTrue(outcome is RegistrationOutcome.Refused, "got $outcome")
    }

    @Test
    fun `a failed registration leaves the device usable and unsigned`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val m = manager()

        m.ensureRegistered()

        assertTrue(!m.isRegistered())
        assertNull(m.signingKey(), "an unsigned device is a normal state, not an error")
        assertTrue(m.deviceId().isNotBlank(), "the device lost its identity on a failed sync")
    }

    // --- rejection ---------------------------------------------------------

    /**
     * A dead token makes every later sync fail identically, so it is discarded.
     * The device id is kept: it is the identity the packets already in the mesh
     * were signed under.
     */
    @Test
    fun `rejected credentials are cleared but the identity is kept`() {
        server.enqueue(ok())
        val m = manager()
        m.ensureRegistered()
        val id = m.deviceId()

        m.onCredentialsRejected()

        assertTrue(!m.isRegistered())
        assertNull(m.deviceToken())
        assertEquals(id, m.deviceId(), "the device changed identity after a 401")
    }

    @Test
    fun `a device can register again after its credentials were rejected`() {
        server.enqueue(ok())
        val m = manager()
        m.ensureRegistered()
        m.onCredentialsRejected()

        server.enqueue(ok())
        assertTrue(m.ensureRegistered() is RegistrationOutcome.Registered)
        assertNotNull(m.signingKey())
    }
}
