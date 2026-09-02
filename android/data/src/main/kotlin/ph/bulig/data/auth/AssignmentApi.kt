package ph.bulig.data.auth

import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import ph.bulig.data.presentation.Assignment
import ph.bulig.data.presentation.ResponderStatus
import ph.bulig.data.sync.HttpSyncApi
import ph.bulig.data.sync.SyncConfig
import ph.bulig.mesh.priority.PriorityLevel

/** Wire shape of `GET /api/v1/me/assignments`. */
@Serializable
data class AssignmentListDto(val assignments: List<AssignmentDto> = emptyList())

@Serializable
data class AssignmentDto(
    val id: Long,
    val status: String? = null,
    @SerialName("assigned_at") val assignedAt: String? = null,
    val emergency: EmergencyDto? = null,
)

@Serializable
data class EmergencyDto(
    @SerialName("emergency_code") val emergencyCode: String? = null,
    @SerialName("type_code") val typeCode: String? = null,
    @SerialName("priority_level") val priorityLevel: String? = null,
    val description: String? = null,
    @SerialName("affected_count") val affectedCount: Int = 1,
    @SerialName("children_count") val childrenCount: Int = 0,
    @SerialName("elderly_count") val elderlyCount: Int = 0,
    @SerialName("mobility_limited_count") val mobilityLimitedCount: Int = 0,
    @SerialName("is_life_threatening") val isLifeThreatening: Boolean = false,
    @SerialName("created_at_device") val createdAtDevice: String? = null,
    @SerialName("received_at_server") val receivedAtServer: String? = null,
    @SerialName("hop_count") val hopCount: Int = 0,
    val type: TypeDto? = null,
    val location: LocationDto? = null,
)

@Serializable
data class TypeDto(val code: String? = null, @SerialName("label_en") val labelEn: String? = null)

@Serializable
data class LocationDto(
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("accuracy_m") val accuracyM: Double? = null,
    val purok: String? = null,
)

/**
 * A responder's assignment queue.
 *
 * Separate from [ph.bulig.data.sync.HttpSyncApi] because it is authenticated as
 * a *person* rather than as a device, and the backend enforces that split
 * deliberately: a relay phone can push packets, but it cannot read anybody's
 * assignments. See `docs/02-roles-permissions.md`.
 *
 * **This is a convenience, not a dependency.** A responder with no signal sees
 * whatever queue was last fetched, and the app does not pretend otherwise —
 * `AssignmentListStateFactory` measures every age from filing, so a stale queue
 * is visibly stale rather than misleadingly fresh.
 */
class AssignmentApi(
    private val config: SyncConfig,
    private val client: OkHttpClient = HttpSyncApi.defaultClient(),
    private val json: Json = HttpSyncApi.defaultJson,
) {

    /**
     * @param activeOnly excludes closed assignments, which is what a responder
     *   walking between calls actually wants to see.
     */
    fun mine(token: String, activeOnly: Boolean = true): List<Assignment> {
        val url = buildString {
            append(config.baseUrl.trimEnd('/'))
            append("/api/v1/me/assignments")
            if (activeOnly) append("?active=1")
        }

        val response = try {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .get()
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer $token")
                    .build()
            ).execute()
        } catch (e: IOException) {
            throw LoginException(LoginFailure.Unreachable(e.message ?: "no connection"))
        }

        response.use {
            val text = try {
                it.body?.string().orEmpty()
            } catch (e: IOException) {
                throw LoginException(LoginFailure.Unreachable("body truncated"))
            }

            if (!it.isSuccessful) {
                throw LoginException(
                    when {
                        it.code == 401 || it.code == 403 -> LoginFailure.WrongCredentials
                        else -> LoginFailure.ServerError(it.code)
                    }
                )
            }

            return try {
                json.decodeFromString(AssignmentListDto.serializer(), text)
                    .assignments
                    .mapNotNull { dto -> dto.toAssignment() }
            } catch (e: Exception) {
                throw LoginException(LoginFailure.ServerError(it.code))
            }
        }
    }
}

/**
 * Maps one wire assignment onto the tested [Assignment] model.
 *
 * Returns null for a row with no emergency attached — an assignment to nothing
 * cannot be acted on, and showing an empty card would waste a responder's
 * attention at the moment it is scarcest.
 *
 * **`filedAtMs` and `receivedAtMs` are kept distinct.** That is the whole reason
 * the model has both: a report that crossed three phones may reach a responder
 * ten minutes after it was written, and every age on screen is measured from
 * filing. Collapsing them here would silently make a mesh-delayed CRITICAL look
 * like it just happened.
 */
internal fun AssignmentDto.toAssignment(): Assignment? {
    val emergency = this.emergency ?: return null

    val filedAt = parseIso(emergency.createdAtDevice)
    val receivedAt = parseIso(emergency.receivedAtServer) ?: filedAt

    return Assignment(
        emergencyCode = emergency.emergencyCode ?: "BLG-$id",
        typeCode = emergency.type?.code ?: emergency.typeCode ?: "OTHER",
        filedAtMs = filedAt ?: receivedAt ?: 0L,
        receivedAtMs = receivedAt ?: filedAt ?: 0L,
        status = responderStatus(status),
        priorityLevel = priorityLevel(emergency.priorityLevel),
        description = emergency.description,
        affectedCount = emergency.affectedCount,
        childrenCount = emergency.childrenCount,
        elderlyCount = emergency.elderlyCount,
        mobilityLimitedCount = emergency.mobilityLimitedCount,
        isLifeThreatening = emergency.isLifeThreatening,
        accuracyM = emergency.location?.accuracyM?.toInt(),
        purok = emergency.location?.purok,
        hopCount = emergency.hopCount,
        // The server holds this assignment, so the responder's status is not
        // owed to it. Local changes flip this back to false until they upload.
        statusSynced = true,
    )
}

/** An unrecognised status is treated as newly assigned, the state with the most options. */
internal fun responderStatus(wire: String?): ResponderStatus = when (wire?.lowercase()) {
    "accepted" -> ResponderStatus.ACCEPTED
    "en_route", "enroute" -> ResponderStatus.EN_ROUTE
    "on_site", "onsite" -> ResponderStatus.ON_SITE
    "resolved", "completed" -> ResponderStatus.RESOLVED
    "declined" -> ResponderStatus.DECLINED
    else -> ResponderStatus.ASSIGNED
}

/**
 * An unrecognised priority becomes MODERATE rather than CRITICAL or LOW.
 *
 * Deliberately the middle: guessing CRITICAL would push a real emergency down
 * the queue behind an unknown, and guessing LOW would bury the unknown itself.
 */
internal fun priorityLevel(wire: String?): PriorityLevel = when (wire?.uppercase()) {
    "CRITICAL" -> PriorityLevel.CRITICAL
    "HIGH" -> PriorityLevel.HIGH
    "LOW" -> PriorityLevel.LOW
    else -> PriorityLevel.MODERATE
}

/** Returns null rather than throwing: a missing timestamp is not a reason to drop a rescue. */
internal fun parseIso(value: String?): Long? = try {
    value?.let { java.time.Instant.parse(it).toEpochMilli() }
} catch (e: Exception) {
    try {
        // Laravel sometimes serialises without a zone designator.
        value?.let {
            java.time.LocalDateTime.parse(it.replace(" ", "T"))
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        }
    } catch (e2: Exception) {
        null
    }
}
