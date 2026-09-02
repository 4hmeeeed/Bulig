package ph.bulig.data.auth

import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ph.bulig.data.sync.HttpSyncApi
import ph.bulig.data.sync.SyncConfig
import ph.bulig.data.sync.SyncException
import ph.bulig.data.sync.SyncFailure

/**
 * Which part of the system a signed-in person belongs to.
 *
 * Unknown roles map to [UNKNOWN] rather than throwing. The server is deployed
 * separately and may gain a role this build has never heard of; refusing to sign
 * somebody in over an unrecognised string would lock out a responder in the
 * middle of a disaster because the backend was updated first.
 */
enum class UserRole(val wire: String) {
    RESIDENT("resident"),
    RESPONDER("responder"),
    OPERATOR("operator"),
    ADMIN("admin"),
    UNKNOWN("");

    /** Whether this role has an assignment queue to be shown. */
    val isResponder: Boolean get() = this == RESPONDER

    /** Command-center roles. The mobile app has no screens for these. */
    val isCommandCenter: Boolean get() = this == OPERATOR || this == ADMIN

    companion object {
        fun fromWire(value: String?): UserRole =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

@Serializable
data class LoginRequestDto(val email: String, val password: String)

@Serializable
data class UserDto(
    val id: Long,
    val name: String,
    val email: String,
    val role: String? = null,
)

@Serializable
data class LoginResponseDto(val token: String, val user: UserDto)

@Serializable
data class ResponderDto(
    val id: Long,
    @SerialName("rescue_team_id") val rescueTeamId: Long? = null,
    val status: String? = null,
    @SerialName("badge_no") val badgeNo: String? = null,
)

@Serializable
data class MeResponseDto(val user: UserDto, val responder: ResponderDto? = null)

/** A signed-in person, as the app holds them. */
data class Session(
    val token: String,
    val userId: Long,
    val name: String,
    val email: String,
    val role: UserRole,
    val badgeNo: String? = null,
    val rescueTeamId: Long? = null,
) {
    /** Never print the token: this object reaches log lines and crash reports. */
    override fun toString(): String = "Session(name=$name, role=$role, token=***)"
}

/** Why a sign-in failed, in terms the screen can act on. */
sealed interface LoginFailure {
    data object WrongCredentials : LoginFailure
    data object AccountDisabled : LoginFailure
    data class Unreachable(val detail: String) : LoginFailure
    data class ServerError(val status: Int) : LoginFailure

    /** Worth offering a retry button for. */
    val isTransient: Boolean
        get() = this is Unreachable || this is ServerError
}

class LoginException(val failure: LoginFailure) : IOException("login failed: $failure")

/**
 * Sign-in, for responders.
 *
 * Residents never sign in. That is deliberate and it is the whole reason this
 * class is small: requiring an account to report an emergency would put a
 * network call in front of the one action that must work with no network at all.
 * Only a responder — who needs an assignment queue that belongs to them — has
 * anything to authenticate for.
 *
 * @see docs/02-roles-permissions.md
 */
class AuthApi(
    private val config: SyncConfig,
    private val client: OkHttpClient = HttpSyncApi.defaultClient(),
    private val json: Json = HttpSyncApi.defaultJson,
) {

    fun login(email: String, password: String): Session {
        val body = json.encodeToString(
            LoginRequestDto.serializer(), LoginRequestDto(email.trim(), password),
        ).toRequestBody(JSON)

        val response = execute(
            Request.Builder()
                .url(endpoint("api/v1/auth/login"))
                .post(body)
                .header("Accept", "application/json")
                .build()
        )

        val parsed = json.decodeFromString(LoginResponseDto.serializer(), response)

        return Session(
            token = parsed.token,
            userId = parsed.user.id,
            name = parsed.user.name,
            email = parsed.user.email,
            role = UserRole.fromWire(parsed.user.role),
        )
    }

    /**
     * Re-reads the signed-in person, including their responder record.
     *
     * Called on launch with a stored token: it is the only way to find out that
     * a token was revoked or a responder was moved to another team while the app
     * was closed.
     */
    fun me(token: String): Session {
        val response = execute(
            Request.Builder()
                .url(endpoint("api/v1/auth/me"))
                .get()
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $token")
                .build()
        )

        val parsed = json.decodeFromString(MeResponseDto.serializer(), response)

        return Session(
            token = token,
            userId = parsed.user.id,
            name = parsed.user.name,
            email = parsed.user.email,
            role = UserRole.fromWire(parsed.user.role),
            badgeNo = parsed.responder?.badgeNo,
            rescueTeamId = parsed.responder?.rescueTeamId,
        )
    }

    /**
     * Best-effort: a failure is swallowed.
     *
     * The local session is cleared by the caller regardless. A responder who
     * taps sign-out on a phone with no signal must still be signed out of *this
     * phone*, which is the part they can see.
     */
    fun logout(token: String) {
        try {
            execute(
                Request.Builder()
                    .url(endpoint("api/v1/auth/logout"))
                    .post(ByteArray(0).toRequestBody(JSON))
                    .header("Authorization", "Bearer $token")
                    .build()
            )
        } catch (e: Exception) {
            // Deliberately ignored — see the note above.
        }
    }

    private fun execute(request: Request): String {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw LoginException(LoginFailure.Unreachable(e.message ?: "no connection"))
        }

        response.use {
            val text = try {
                it.body?.string().orEmpty()
            } catch (e: IOException) {
                throw LoginException(LoginFailure.Unreachable("body truncated"))
            }

            if (it.isSuccessful) return text

            throw LoginException(
                when {
                    // Laravel answers a bad password with a 422 validation error,
                    // and a disabled account with an explicit 403 code. The two
                    // must not be conflated: one is worth retyping, the other is
                    // worth phoning the barangay about.
                    it.code == 403 && text.contains("ACCOUNT_DISABLED") ->
                        LoginFailure.AccountDisabled

                    it.code == 401 || it.code == 403 || it.code == 422 ->
                        LoginFailure.WrongCredentials

                    it.code >= 500 -> LoginFailure.ServerError(it.code)

                    else -> LoginFailure.ServerError(it.code)
                }
            )
        }
    }

    private fun endpoint(path: String) =
        "${config.baseUrl.trimEnd('/')}/${path.trimStart('/')}"

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Where a signed-in session is kept between launches. */
interface SessionStore {
    fun load(): Session?
    fun save(session: Session)
    fun clear()
}

class InMemorySessionStore(private var current: Session? = null) : SessionStore {
    override fun load(): Session? = current
    override fun save(session: Session) { current = session }
    override fun clear() { current = null }
}
