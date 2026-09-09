package ph.bulig.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format for `POST /api/v1/sync/packets`.
 *
 * Field names and nullability mirror `SyncPacketsRequest` in the Laravel backend
 * exactly. This is a contract between two codebases in two languages, so it is
 * verified by an end-to-end test that generates JSON here and feeds it to the
 * real validator — not by reading both files and hoping.
 *
 * @see docs/05-api-contract.md 5.2
 */
@Serializable
data class SyncRequestDto(
    /**
     * This device's clock at the moment the batch was assembled. The server
     * subtracts its own clock to measure drift, which is what makes transmission
     * delay meaningful for an offline phone.
     */
    @SerialName("client_clock") val clientClock: String,
    val packets: List<PacketDto>,
)

@Serializable
data class PacketDto(
    @SerialName("packet_id") val packetId: String,
    @SerialName("emergency_id") val emergencyId: String,
    @SerialName("origin_device_id") val originDeviceId: String,
    @SerialName("hop_count") val hopCount: Int,
    @SerialName("ttl_remaining") val ttlRemaining: Int,
    @SerialName("ttl_initial") val ttlInitial: Int,
    @SerialName("created_at_device") val createdAtDevice: String,
    val hmac: String? = null,
    @SerialName("route_path") val routePath: List<String>? = null,
    val payload: PayloadDto,
)

@Serializable
data class PayloadDto(
    @SerialName("type_code") val typeCode: String,
    val description: String? = null,
    @SerialName("affected_count") val affectedCount: Int,
    @SerialName("children_count") val childrenCount: Int = 0,
    @SerialName("elderly_count") val elderlyCount: Int = 0,
    @SerialName("mobility_limited_count") val mobilityLimitedCount: Int = 0,
    @SerialName("is_life_threatening") val isLifeThreatening: Boolean = false,
    @SerialName("vulnerability_notes") val vulnerabilityNotes: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("accuracy_m") val accuracyM: Double? = null,
    @SerialName("location_provider") val locationProvider: String? = null,
    @SerialName("captured_at") val capturedAt: String? = null,
)

@Serializable
data class SyncResponseDto(
    @SerialName("server_time") val serverTime: String,
    @SerialName("clock_offset_ms") val clockOffsetMs: Long = 0,
    @SerialName("sync_log_id") val syncLogId: Long? = null,
    val results: List<PacketResultDto> = emptyList(),
    val summary: SyncSummaryDto? = null,
)

@Serializable
data class PacketResultDto(
    @SerialName("packet_id") val packetId: String,
    val status: String,
    @SerialName("emergency_code") val emergencyCode: String? = null,
    @SerialName("priority_level") val priorityLevel: String? = null,
    val reason: String? = null,
)

@Serializable
data class SyncSummaryDto(
    val accepted: Int = 0,
    val duplicate: Int = 0,
    val rejected: Int = 0,
)

/**
 * Per-packet outcomes the server can report.
 *
 * The distinction that matters is [isPermanent]: a transient failure keeps the
 * packet queued for another attempt, while a permanent one must not be retried
 * forever. A device with a rejected packet retrying every 30 seconds for a week
 * is a battery drain in a barangay that cannot afford one.
 */
enum class PacketOutcome(val wire: String) {
    ACCEPTED("ACCEPTED"),
    DUPLICATE("DUPLICATE"),
    TTL_EXPIRED_ACCEPTED("TTL_EXPIRED_ACCEPTED"),
    INVALID_HMAC("INVALID_HMAC"),
    REJECTED("REJECTED"),
    UNKNOWN("UNKNOWN");

    /** The server has it, or has decided it never will. Stop sending it. */
    val isSettled: Boolean
        get() = this != UNKNOWN

    /** Retrying would produce the same answer. */
    val isPermanent: Boolean
        get() = this == INVALID_HMAC || this == REJECTED

    /** The server holds this packet — whether it was new or already known. */
    val isDelivered: Boolean
        get() = this == ACCEPTED || this == DUPLICATE || this == TTL_EXPIRED_ACCEPTED

    companion object {
        fun fromWire(value: String): PacketOutcome =
            entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}
