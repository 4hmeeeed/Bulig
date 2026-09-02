package ph.bulig.data.sync

import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Wire format for `POST /api/v1/devices/register`. */
@Serializable
data class RegisterRequestDto(
    @SerialName("device_id") val deviceId: String,
    val model: String? = null,
    @SerialName("android_version") val androidVersion: String? = null,
    val label: String? = null,
    @SerialName("supports_advertising") val supportsAdvertising: Boolean = true,
)

@Serializable
data class RegisterResponseDto(
    @SerialName("device_token") val deviceToken: String,
    /** Hex-encoded. Returned exactly once, and never again. */
    @SerialName("hmac_key") val hmacKey: String,
    @SerialName("server_time") val serverTime: String,
    @SerialName("ttl_initial") val ttlInitial: Int = 10,
)

/** What a device holds after registering. Both halves are secrets. */
data class DeviceCredentials(
    val deviceId: String,
    val token: String,
    val hmacKey: ByteArray,
    val ttlInitial: Int,
) {
    // ByteArray identity is referential, which would make two identical
    // credentials compare unequal and quietly break any caching around them.
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is DeviceCredentials &&
                deviceId == other.deviceId &&
                token == other.token &&
                ttlInitial == other.ttlInitial &&
                hmacKey.contentEquals(other.hmacKey)
            )

    override fun hashCode(): Int =
        (31 * (31 * deviceId.hashCode() + token.hashCode())) + hmacKey.contentHashCode()

    /** Never log credentials. The default data-class toString would print the key. */
    override fun toString(): String = "DeviceCredentials(deviceId=$deviceId, token=***, key=***)"
}

/**
 * Registers this phone and collects its signing key.
 *
 * **Registration is not a prerequisite for reporting.** A phone that has never
 * reached the server can still create and relay packets; the server simply
 * records `hmac_valid` as null for them. Requiring registration first would
 * reintroduce the internet dependency this whole architecture exists to remove,
 * and would mean a resident who installed the app during the storm could not
 * file anything. So this runs opportunistically, whenever connectivity happens,
 * and its failure is never allowed to block the report flow.
 *
 * What registration buys is *authentication*: until it succeeds, packets are
 * unsigned and the server cannot tell a genuine report from a forged one.
 */
class DeviceRegistrar(
    private val config: SyncConfig,
    private val client: OkHttpClient = HttpSyncApi.defaultClient(),
    private val json: Json = HttpSyncApi.defaultJson,
) {

    /**
     * @throws SyncException with a [SyncFailure] the caller can act on.
     *
     * Re-registering an existing `device_id` rotates the key and invalidates
     * prior tokens, which is how a lost phone is cut off — so this must not be
     * called casually on every launch.
     */
    fun register(request: RegisterRequestDto): DeviceCredentials {
        val body = json.encodeToString(RegisterRequestDto.serializer(), request)
            .toRequestBody(JSON_MEDIA_TYPE)

        val http = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/api/v1/devices/register")
            .post(body)
            .header("Accept", "application/json")
            .build()

        val response = try {
            client.newCall(http).execute()
        } catch (e: IOException) {
            throw SyncException(SyncFailure.Unreachable(e.message ?: "no connection"))
        }

        response.use {
            val text = try {
                it.body?.string().orEmpty()
            } catch (e: IOException) {
                throw SyncException(SyncFailure.Unreachable("body truncated: ${e.message}"))
            }

            if (!it.isSuccessful) {
                // 403 here means the device was revoked, which is a decision an
                // operator made deliberately. Retrying cannot undo it.
                throw SyncException(
                    if (it.code == 403) {
                        SyncFailure.Rejected(403, "device revoked")
                    } else if (it.code >= 500) {
                        SyncFailure.ServerError(it.code)
                    } else {
                        SyncFailure.Rejected(it.code, text.take(200))
                    }
                )
            }

            val parsed = try {
                json.decodeFromString(RegisterResponseDto.serializer(), text)
            } catch (e: Exception) {
                throw SyncException(SyncFailure.Unreadable(e.message ?: "unparseable response"))
            }

            val key = decodeHex(parsed.hmacKey)
                ?: throw SyncException(SyncFailure.Unreadable("hmac_key is not valid hex"))

            // A short key would silently weaken every signature this device ever
            // produces, and nothing downstream would notice.
            if (key.size < MINIMUM_KEY_BYTES) {
                throw SyncException(
                    SyncFailure.Unreadable("hmac_key is ${key.size} bytes, expected $MINIMUM_KEY_BYTES")
                )
            }

            return DeviceCredentials(
                deviceId = request.deviceId,
                token = parsed.deviceToken,
                hmacKey = key,
                ttlInitial = parsed.ttlInitial,
            )
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** The server issues 32 random bytes. Anything less is a bug or an attack. */
        const val MINIMUM_KEY_BYTES = 32

        /** Returns null rather than throwing, so a malformed key is a handled failure. */
        internal fun decodeHex(value: String): ByteArray? {
            if (value.length % 2 != 0 || value.isEmpty()) return null

            return try {
                ByteArray(value.length / 2) { i ->
                    val byte = value.substring(i * 2, i * 2 + 2)
                    val parsed = byte.toInt(16)
                    parsed.toByte()
                }
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}
