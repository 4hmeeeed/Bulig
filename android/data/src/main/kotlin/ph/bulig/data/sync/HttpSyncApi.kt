package ph.bulig.data.sync

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Why a sync attempt failed, in the only terms the caller can act on.
 *
 * A single `IOException` would tell [SyncCoordinator] nothing about whether to
 * try again in thirty seconds or stop trying at all — and a phone retrying a
 * permanently rejected batch every thirty seconds for a week is a battery drain
 * in a barangay that cannot afford one.
 */
sealed interface SyncFailure {
    /** No usable connection, or the server never answered. Try again later. */
    data class Unreachable(val detail: String) : SyncFailure

    /** The server answered, and it was broken. Its problem, not ours. Retry. */
    data class ServerError(val status: Int) : SyncFailure

    /** The device token is missing, expired, or revoked. Re-register. */
    data object Unauthorized : SyncFailure

    /** The server rejected the batch's shape. Retrying sends the same bytes. */
    data class Rejected(val status: Int, val detail: String) : SyncFailure

    /** A 2xx whose body we could not read. */
    data class Unreadable(val detail: String) : SyncFailure

    /** Whether trying the same request again could plausibly succeed. */
    val isTransient: Boolean
        get() = this is Unreachable || this is ServerError
}

class SyncException(val failure: SyncFailure) :
    IOException("sync failed: $failure")

/**
 * Where the phone is pointed and how it identifies itself.
 *
 * [deviceToken] is null until registration succeeds. That is a normal state,
 * not an error: a device that has never reached the server can still create and
 * relay packets, and requiring registration first would reintroduce the very
 * internet dependency this architecture exists to remove.
 */
data class SyncConfig(
    val baseUrl: String,
    val deviceToken: String? = null,
)

/**
 * The real HTTP implementation of [SyncApi].
 *
 * This is the only place in the Android codebase that opens a socket, and it is
 * deliberately narrow: it performs exactly one request, maps the answer, and
 * returns. It contains **no retry logic of its own** — [SyncCoordinator] owns
 * backoff, and a second retry loop hidden down here would multiply against it
 * and turn a 30-second backoff into a burst of requests from a phone whose
 * battery is the scarcest thing it has.
 *
 * Plain OkHttp rather than Retrofit: the failure taxonomy above needs status
 * codes, which Retrofit collapses into `HttpException`, and one dependency beats
 * three. It is a JVM library, so this class and its tests run in `:data` with no
 * Android at all — which is why the sync path can be tested against a real
 * server instead of a fake that only agrees with itself.
 *
 * @see docs/05-api-contract.md 5.2
 * @see docs/07-offline-sync.md 7.4
 */
class HttpSyncApi(
    private val config: SyncConfig,
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = defaultJson,
) : SyncApi {

    override fun pushPackets(request: SyncRequestDto): SyncResponseDto {
        val body = json.encodeToString(SyncRequestDto.serializer(), request)
            .toRequestBody(JSON_MEDIA_TYPE)

        val http = Request.Builder()
            .url(endpoint("api/v1/sync/packets"))
            .post(body)
            .header("Accept", "application/json")
            .apply { config.deviceToken?.let { header("Authorization", "Bearer $it") } }
            .build()

        val response = try {
            client.newCall(http).execute()
        } catch (e: IOException) {
            // Covers no route to host, DNS failure, TLS failure and timeout —
            // every one of which is ordinary for this app, not exceptional.
            throw SyncException(SyncFailure.Unreachable(e.message ?: e::class.simpleName.orEmpty()))
        }

        response.use {
            val text = try {
                it.body?.string().orEmpty()
            } catch (e: IOException) {
                throw SyncException(SyncFailure.Unreachable("body truncated: ${e.message}"))
            }

            if (!it.isSuccessful) throw SyncException(failureFor(it.code, text))

            return try {
                json.decodeFromString(SyncResponseDto.serializer(), text)
            } catch (e: Exception) {
                // A 2xx we cannot parse is worse than an error status: it would
                // otherwise be read as "every packet delivered".
                throw SyncException(SyncFailure.Unreadable(e.message ?: "unparseable response"))
            }
        }
    }

    private fun failureFor(status: Int, body: String): SyncFailure = when {
        status == 401 || status == 403 -> SyncFailure.Unauthorized

        // 429 is the server asking for room. Transient by definition, and the
        // coordinator's backoff is what gives it that room.
        status == 429 -> SyncFailure.ServerError(status)

        status >= 500 -> SyncFailure.ServerError(status)

        else -> SyncFailure.Rejected(status, body.take(REJECTION_DETAIL_LIMIT))
    }

    private fun endpoint(path: String): String =
        "${config.baseUrl.trimEnd('/')}/${path.trimStart('/')}"

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Enough of the server's complaint to diagnose, not enough to log a report. */
        private const val REJECTION_DETAIL_LIMIT = 200

        /**
         * Unknown response fields are ignored rather than fatal.
         *
         * The server is deployed separately from the app and will gain fields
         * this build has never heard of. Failing on them would mean a backend
         * release could stop every phone in the barangay from syncing.
         */
        val defaultJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        /**
         * Timeouts sized for a phone on a barely-working connection.
         *
         * Generous, because the alternative to a slow sync is no sync — but
         * bounded, because a socket held open forever is a wakelock this app
         * cannot justify. Retries are the coordinator's job, so they are off.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
