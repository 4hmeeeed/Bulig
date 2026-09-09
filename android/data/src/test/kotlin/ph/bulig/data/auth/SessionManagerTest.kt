package ph.bulig.data.auth

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import ph.bulig.data.sync.SyncConfig

class UserRoleTest {

    @Test
    fun `every known role maps from its wire value`() {
        assertEquals(UserRole.RESIDENT, UserRole.fromWire("resident"))
        assertEquals(UserRole.RESPONDER, UserRole.fromWire("responder"))
        assertEquals(UserRole.OPERATOR, UserRole.fromWire("operator"))
        assertEquals(UserRole.ADMIN, UserRole.fromWire("admin"))
    }

    /**
     * The server is deployed separately and may gain a role this build has never
     * heard of. Throwing would lock a responder out mid-disaster because the
     * backend was updated first.
     */
    @Test
    fun `an unrecognised role degrades instead of throwing`() {
        assertEquals(UserRole.UNKNOWN, UserRole.fromWire("barangay_captain"))
        assertEquals(UserRole.UNKNOWN, UserRole.fromWire(null))
        assertEquals(UserRole.UNKNOWN, UserRole.fromWire(""))
    }

    @Test
    fun `only a responder has an assignment queue`() {
        assertTrue(UserRole.RESPONDER.isResponder)
        UserRole.entries.filter { it != UserRole.RESPONDER }.forEach {
            assertTrue(!it.isResponder, "$it claimed an assignment queue")
        }
    }

    @Test
    fun `operators and admins belong to the command center`() {
        assertTrue(UserRole.OPERATOR.isCommandCenter)
        assertTrue(UserRole.ADMIN.isCommandCenter)
        assertTrue(!UserRole.RESIDENT.isCommandCenter)
        assertTrue(!UserRole.RESPONDER.isCommandCenter)
    }
}

class SessionManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var store: InMemorySessionStore

    @BeforeTest
    fun start() {
        server = MockWebServer()
        server.start()
        store = InMemorySessionStore()
    }

    @AfterTest
    fun stop() = server.shutdown()

    private fun manager() = SessionManager(
        api = AuthApi(SyncConfig(server.url("/").toString())),
        store = store,
    )

    private fun loginOk(role: String = "responder") = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"token":"9|abcdef",
                "user":{"id":4,"name":"Tanod R. Cinco","email":"r@example.com","role":"$role"}}"""
                .replace("\n", "")
        )

    private fun meOk(role: String = "responder", badge: String = "T-14") = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"user":{"id":4,"name":"Tanod R. Cinco","email":"r@example.com","role":"$role"},
                "responder":{"id":2,"rescue_team_id":1,"status":"available","badge_no":"$badge"}}"""
                .replace("\n", "")
        )

    // --- the default ------------------------------------------------------

    /**
     * The most important assertion in this file. A resident never signs in,
     * because requiring an account to report an emergency would put a network
     * call in front of the one action that must work with no network.
     */
    @Test
    fun `nobody signed in means the resident experience`() {
        assertEquals(AppMode.Resident, manager().currentMode())
        assertTrue(!manager().isSignedIn())
        assertEquals(0, server.requestCount, "an unsigned-in launch touched the network")
    }

    // --- signing in -------------------------------------------------------

    @Test
    fun `a responder signing in gets the responder experience`() {
        server.enqueue(loginOk())

        val result = manager().signIn("r@example.com", "secret")

        assertTrue(result is SignInResult.Success, "got $result")
        assertTrue(result.mode is AppMode.Responder)
        assertEquals("Tanod R. Cinco", (result.mode as AppMode.Responder).session.name)
    }

    @Test
    fun `the session survives into the next launch`() {
        server.enqueue(loginOk())
        manager().signIn("r@example.com", "secret")

        assertTrue(manager().currentMode() is AppMode.Responder, "the session was not kept")
    }

    /**
     * The mobile app has no operator screens. Saying so is better than dropping
     * them onto a resident's home as though the sign-in had failed.
     */
    @Test
    fun `an operator is told the app has nothing for them`() {
        server.enqueue(loginOk(role = "operator"))

        val result = manager().signIn("o@example.com", "secret") as SignInResult.Success

        assertTrue(result.mode is AppMode.CommandCenterOnly)
    }

    /** A resident account signing in is still just a resident. */
    @Test
    fun `a resident account gets the resident experience`() {
        server.enqueue(loginOk(role = "resident"))

        val result = manager().signIn("x@example.com", "secret") as SignInResult.Success

        assertEquals(AppMode.Resident, result.mode)
    }

    @Test
    fun `an unknown role falls back to the resident experience`() {
        server.enqueue(loginOk(role = "barangay_captain"))

        val result = manager().signIn("x@example.com", "secret") as SignInResult.Success

        assertEquals(
            AppMode.Resident, result.mode,
            "an unrecognised role must still be able to report an emergency",
        )
    }

    // --- failing to sign in -------------------------------------------------

    /** Laravel answers a bad password with a 422 validation error. */
    @Test
    fun `wrong credentials are reported as wrong credentials`() {
        server.enqueue(
            MockResponse().setResponseCode(422)
                .setBody("""{"message":"These credentials do not match our records."}""")
        )

        val result = manager().signIn("r@example.com", "wrong") as SignInResult.Failed

        assertEquals(LoginFailure.WrongCredentials, result.failure)
        assertTrue(!result.failure.isTransient, "a wrong password is not worth auto-retrying")
    }

    /**
     * A disabled account and a wrong password must not be conflated: one is
     * worth retyping, the other is worth phoning the barangay about.
     */
    @Test
    fun `a disabled account is distinguished from a wrong password`() {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"message":"This account is disabled.","code":"ACCOUNT_DISABLED"}""")
        )

        val result = manager().signIn("r@example.com", "secret") as SignInResult.Failed

        assertEquals(LoginFailure.AccountDisabled, result.failure)
    }

    @Test
    fun `an unreachable server is transient and worth a retry button`() {
        server.shutdown()

        val result = manager().signIn("r@example.com", "secret") as SignInResult.Failed

        assertTrue(result.failure is LoginFailure.Unreachable, "got ${result.failure}")
        assertTrue(result.failure.isTransient)
    }

    /**
     * The rule that protects the product: a failed sign-in leaves a working
     * app, not a locked one.
     */
    @Test
    fun `a failed sign-in leaves the resident experience intact`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val m = manager()

        m.signIn("r@example.com", "secret")

        assertEquals(AppMode.Resident, m.currentMode())
        assertTrue(!m.isSignedIn())
    }

    // --- refreshing ---------------------------------------------------------

    @Test
    fun `refresh picks up the responder record the login did not carry`() {
        server.enqueue(loginOk())
        val m = manager()
        m.signIn("r@example.com", "secret")

        server.enqueue(meOk(badge = "T-14"))
        val mode = m.refresh() as AppMode.Responder

        assertEquals("T-14", mode.session.badgeNo)
        assertEquals(1L, mode.session.rescueTeamId)
    }

    /**
     * The instinct to sign somebody out when the server cannot be reached is
     * exactly wrong in this system: the whole project exists because
     * connectivity is unreliable. A responder in a barangay with no signal must
     * still see their queue.
     */
    @Test
    fun `an unreachable server on refresh keeps the session`() {
        server.enqueue(loginOk())
        val m = manager()
        m.signIn("r@example.com", "secret")

        server.shutdown()

        assertTrue(m.refresh() is AppMode.Responder, "a signal outage signed a responder out")
        assertTrue(m.isSignedIn())
    }

    @Test
    fun `a revoked token on refresh clears the session`() {
        server.enqueue(loginOk())
        val m = manager()
        m.signIn("r@example.com", "secret")

        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(AppMode.Resident, m.refresh())
        assertTrue(!m.isSignedIn(), "a revoked token was kept")
    }

    @Test
    fun `refreshing with nobody signed in touches nothing`() {
        assertEquals(AppMode.Resident, manager().refresh())
        assertEquals(0, server.requestCount)
    }

    // --- signing out --------------------------------------------------------

    /**
     * Local first. A responder tapping sign-out on a phone with no signal must
     * still be signed out of the phone in front of them — the part they can see,
     * and the part that matters if they are handing it to somebody else.
     */
    @Test
    fun `signing out works even when the server cannot be told`() {
        server.enqueue(loginOk())
        val m = manager()
        m.signIn("r@example.com", "secret")

        server.shutdown()
        m.signOut()

        assertTrue(!m.isSignedIn())
        assertEquals(AppMode.Resident, m.currentMode())
    }

    @Test
    fun `signing out tells the server when it can`() {
        server.enqueue(loginOk())
        val m = manager()
        m.signIn("r@example.com", "secret")
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"message":"Signed out."}"""))
        m.signOut()

        val logout = server.takeRequest()
        assertEquals("/api/v1/auth/logout", logout.path)
        assertEquals("Bearer 9|abcdef", logout.getHeader("Authorization"))
    }

    // --- secrets ------------------------------------------------------------

    @Test
    fun `a session never prints its token`() {
        val text = Session(
            token = "9|abcdef", userId = 4, name = "Tanod R. Cinco",
            email = "r@example.com", role = UserRole.RESPONDER,
        ).toString()

        assertTrue(!text.contains("abcdef"), "the token was printed")
        assertTrue(text.contains("Tanod R. Cinco"))
    }

    @Test
    fun `the login request goes to the contracted endpoint`() {
        server.enqueue(loginOk())
        manager().signIn("  r@example.com  ", "secret")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/auth/login", request.path)
        // Trimmed: a keyboard's trailing space must not fail a sign-in.
        assertTrue(request.body.readUtf8().contains("\"r@example.com\""))
    }
}
