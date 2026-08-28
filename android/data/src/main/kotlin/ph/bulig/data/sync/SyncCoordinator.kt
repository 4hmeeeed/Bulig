package ph.bulig.data.sync

import kotlin.math.min
import kotlin.math.pow
import ph.bulig.data.delivery.DeliveryEvidence
import ph.bulig.data.delivery.DeliveryStateMachine
import ph.bulig.data.model.LocalReport
import ph.bulig.data.store.ReportStore
import ph.bulig.mesh.Clock

/** The network boundary. Implemented with Retrofit on-device, faked in tests. */
fun interface SyncApi {
    /** Throws on transport failure; returns the parsed body on any HTTP 200. */
    fun pushPackets(request: SyncRequestDto): SyncResponseDto
}

/** Whether the radio currently has a usable path to the server. */
fun interface Connectivity {
    fun isOnline(): Boolean
}

data class SyncOutcome(
    val attempted: Int,
    val accepted: Int,
    val duplicate: Int,
    val rejected: Int,
    val failed: Boolean = false,
    val error: String? = null,
) {
    val settled: Int get() = accepted + duplicate
}

/**
 * Decides what to upload, in what order, and when to try again.
 *
 * Two decisions here are worth more than they look:
 *
 * **Ordering.** Batches go out highest-priority first, then oldest first. A
 * connectivity window during a typhoon may be seconds long — a single bar of
 * signal from a passing gap in the weather — and whatever is at the front of the
 * queue is what survives it. Sorting by age alone would let a stale
 * infrastructure report crowd out a cardiac arrest.
 *
 * **Jitter.** When a barangay regains signal, every phone regains it at the same
 * moment. Without randomised backoff they would retry in lockstep and flatten a
 * server that a barangay can barely afford to run.
 *
 * @see docs/07-offline-sync.md 7.4
 */
class SyncCoordinator(
    private val store: ReportStore,
    private val api: SyncApi,
    private val connectivity: Connectivity,
    private val stateMachine: DeliveryStateMachine,
    private val clock: Clock = Clock.SYSTEM,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val backoff: BackoffPolicy = BackoffPolicy(),
) {

    /**
     * Uploads one batch, if there is anything to send and a path to send it on.
     *
     * Never throws: a failed sync is an ordinary event on this system, and the
     * caller is a background worker that should reschedule rather than crash.
     */
    fun syncOnce(nowMs: Long = clock.nowMs()): SyncOutcome {
        if (!connectivity.isOnline()) {
            return SyncOutcome(0, 0, 0, 0, failed = true, error = "offline")
        }

        val batch = selectBatch(nowMs)
        if (batch.isEmpty()) return SyncOutcome(0, 0, 0, 0)

        val request = SyncRequestDto(
            clientClock = Iso8601.format(nowMs),
            packets = batch.map { it.toDto() },
        )

        val response = try {
            api.pushPackets(request)
        } catch (e: Exception) {
            // Transport failure says nothing about the packets themselves, so
            // they all stay queued and the attempt count grows.
            batch.forEach { recordAttempt(it, nowMs) }
            return SyncOutcome(
                batch.size, 0, 0, 0,
                failed = true,
                error = e.message ?: e::class.simpleName,
            )
        }

        return applyResults(batch, response, nowMs)
    }

    /**
     * Highest priority first, then oldest first, excluding anything still inside
     * its backoff window.
     */
    internal fun selectBatch(nowMs: Long): List<LocalReport> =
        store.pendingSync()
            .filter { backoff.isReady(it, nowMs) }
            .sortedWith(
                compareByDescending<LocalReport> { priorityRank(it) }
                    .thenBy { it.packet.createdAtDeviceMs }
            )
            .take(batchSize)

    private fun applyResults(
        batch: List<LocalReport>,
        response: SyncResponseDto,
        nowMs: Long,
    ): SyncOutcome {
        val byId = response.results.associateBy { it.packetId }
        var accepted = 0
        var duplicate = 0
        var rejected = 0

        batch.forEach { report ->
            val result = byId[report.packetId.value]

            if (result == null) {
                // The server did not mention this packet. Treat that as unsettled
                // rather than assuming either outcome.
                recordAttempt(report, nowMs)
                return@forEach
            }

            when (val outcome = PacketOutcome.fromWire(result.status)) {
                PacketOutcome.ACCEPTED, PacketOutcome.TTL_EXPIRED_ACCEPTED -> {
                    accepted++
                    stateMachine.apply(
                        report.packetId,
                        DeliveryEvidence.ServerAcknowledged(
                            emergencyCode = result.emergencyCode,
                            priorityLevel = result.priorityLevel,
                        ),
                    )
                }

                PacketOutcome.DUPLICATE -> {
                    // The server already holds it. That is delivery, not failure.
                    duplicate++
                    stateMachine.apply(
                        report.packetId,
                        DeliveryEvidence.ServerAcknowledged(
                            emergencyCode = result.emergencyCode,
                            priorityLevel = result.priorityLevel,
                        ),
                    )
                }

                else -> {
                    rejected++
                    if (outcome.isPermanent) {
                        stateMachine.markPermanentFailure(
                            report.packetId,
                            result.reason ?: outcome.wire,
                        )
                    } else {
                        recordAttempt(report, nowMs)
                    }
                }
            }
        }

        return SyncOutcome(batch.size, accepted, duplicate, rejected)
    }

    private fun recordAttempt(report: LocalReport, nowMs: Long) {
        store.upsert(
            report.copy(
                attemptCount = report.attemptCount + 1,
                lastAttemptAtMs = nowMs,
            )
        )
    }

    /** When the worker should next wake, given what is still queued. */
    fun nextAttemptDelayMs(nowMs: Long = clock.nowMs()): Long? {
        val pending = store.pendingSync()
        if (pending.isEmpty()) return null

        return pending.minOf { backoff.delayFor(it, nowMs) }
    }

    private fun priorityRank(report: LocalReport): Int = when (report.priorityLevel) {
        "CRITICAL" -> 4
        "HIGH" -> 3
        "MODERATE" -> 2
        "LOW" -> 1
        // Unscored reports sort above LOW: a report the server has not yet rated
        // might be critical, and guessing low would be the costly mistake.
        else -> if (report.packet.payload.isLifeThreatening) 4 else 2
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 50
    }
}

/**
 * Exponential backoff with jitter.
 *
 * The jitter is not decoration. Every phone in a barangay regains signal at
 * roughly the same instant, and synchronised retries would arrive as one spike.
 */
class BackoffPolicy(
    private val baseDelayMs: Long = 30_000,
    private val maxDelayMs: Long = 15 * 60_000,
    private val jitterRatio: Double = 0.25,
    private val random: () -> Double = { Math.random() },
) {

    fun delayFor(report: LocalReport, nowMs: Long): Long {
        if (report.attemptCount == 0) return 0

        val exponential = baseDelayMs * 2.0.pow(report.attemptCount - 1)
        val capped = min(exponential, maxDelayMs.toDouble())

        // Symmetric jitter around the capped delay.
        val jitter = capped * jitterRatio * (random() * 2 - 1)
        val target = (capped + jitter).toLong().coerceAtLeast(0)

        val readyAt = (report.lastAttemptAtMs ?: nowMs) + target
        return (readyAt - nowMs).coerceAtLeast(0)
    }

    fun isReady(report: LocalReport, nowMs: Long): Boolean = delayFor(report, nowMs) == 0L
}

/** Minimal ISO-8601 UTC formatting, so the module stays dependency-light. */
object Iso8601 {
    fun format(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"))

    fun parse(value: String): Long = java.time.Instant.parse(value).toEpochMilli()
}

/** Maps a locally held report onto the sync wire format. */
fun LocalReport.toDto(): PacketDto = PacketDto(
    packetId = packet.packetId.value,
    emergencyId = packet.emergencyId.value,
    originDeviceId = packet.originDeviceId.value,
    hopCount = packet.hopCount,
    ttlRemaining = packet.ttlRemaining,
    ttlInitial = packet.ttlInitial,
    createdAtDevice = Iso8601.format(packet.createdAtDeviceMs),
    hmac = packet.hmac,
    routePath = packet.routePath.map { it.value }.ifEmpty { null },
    payload = PayloadDto(
        typeCode = packet.payload.typeCode,
        description = packet.payload.description,
        affectedCount = packet.payload.affectedCount,
        childrenCount = packet.payload.childrenCount,
        elderlyCount = packet.payload.elderlyCount,
        mobilityLimitedCount = packet.payload.mobilityLimitedCount,
        isLifeThreatening = packet.payload.isLifeThreatening,
        vulnerabilityNotes = packet.payload.vulnerabilityNotes,
        latitude = packet.payload.latitude,
        longitude = packet.payload.longitude,
        accuracyM = packet.payload.accuracyM,
        locationProvider = packet.payload.locationProvider,
        capturedAt = packet.payload.capturedAtMs?.let { Iso8601.format(it) },
    ),
)

