package ph.bulig.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ph.bulig.data.delivery.DeliveryEvidence
import ph.bulig.data.delivery.DeliveryStateMachine
import ph.bulig.data.model.LocalReport
import ph.bulig.data.store.InMemoryReportStore
import ph.bulig.mesh.MutableClock
import ph.bulig.mesh.delivery.DeliveryState
import ph.bulig.mesh.model.DeviceId
import ph.bulig.mesh.model.EmergencyId
import ph.bulig.mesh.model.EmergencyPayload
import ph.bulig.mesh.model.MeshPacket
import ph.bulig.mesh.model.PacketId

/**
 * Uploading: what goes first, what stays queued, and what is never retried.
 *
 * @see docs/07-offline-sync.md 7.4, 7.5
 */
class SyncCoordinatorTest {

    private val clock = MutableClock(1_787_802_731_000)
    private val store = InMemoryReportStore()
    private val stateMachine = DeliveryStateMachine(store)

    private var online = true
    private val connectivity = Connectivity { online }

    private val sent = mutableListOf<SyncRequestDto>()
    private var respond: (SyncRequestDto) -> SyncResponseDto = { request ->
        acceptAll(request)
    }
    private val api = SyncApi { request ->
        sent += request
        respond(request)
    }

    private fun coordinator(batchSize: Int = 50) = SyncCoordinator(
        store = store,
        api = api,
        connectivity = connectivity,
        stateMachine = stateMachine,
        clock = clock,
        batchSize = batchSize,
        // Jitter fixed at the midpoint so backoff windows are deterministic.
        backoff = BackoffPolicy(random = { 0.5 }),
    )

    private fun acceptAll(request: SyncRequestDto) = SyncResponseDto(
        serverTime = Iso8601.format(clock.nowMs()),
        results = request.packets.map {
            PacketResultDto(it.packetId, "ACCEPTED", "BLG-2026-0001", "CRITICAL")
        },
        summary = SyncSummaryDto(accepted = request.packets.size),
    )

    private fun report(
        n: Int,
        priority: String? = null,
        createdAtMs: Long = clock.nowMs(),
        lifeThreatening: Boolean = false,
        attempts: Int = 0,
        lastAttemptAtMs: Long? = null,
    ): LocalReport {
        val r = LocalReport(
            packet = MeshPacket(
                packetId = PacketId("00000000-0000-4000-8000-%012d".format(n)),
                emergencyId = EmergencyId("11111111-0000-4000-8000-%012d".format(n)),
                originDeviceId = DeviceId("this-device"),
                createdAtDeviceMs = createdAtMs,
                payload = EmergencyPayload(
                    typeCode = "FLOOD",
                    isLifeThreatening = lifeThreatening,
                ),
            ),
            priorityLevel = priority,
            attemptCount = attempts,
            lastAttemptAtMs = lastAttemptAtMs,
        )
        store.upsert(r)
        return r
    }

    // --- ordering ---------------------------------------------------------

    /**
     * A connectivity window may last seconds. Whatever is at the front of the
     * queue is what survives it, so priority has to beat age.
     */
    @Test
    fun `critical reports are uploaded before older low priority ones`() {
        report(1, priority = "LOW", createdAtMs = clock.nowMs() - 3_600_000)
        report(2, priority = "MODERATE", createdAtMs = clock.nowMs() - 1_800_000)
        report(3, priority = "CRITICAL", createdAtMs = clock.nowMs())

        val order = coordinator().selectBatch(clock.nowMs()).map { it.packetId.value.takeLast(1) }

        assertEquals(listOf("3", "2", "1"), order)
    }

    @Test
    fun `reports of equal priority go oldest first`() {
        report(1, priority = "HIGH", createdAtMs = clock.nowMs())
        report(2, priority = "HIGH", createdAtMs = clock.nowMs() - 600_000)

        val order = coordinator().selectBatch(clock.nowMs()).map { it.packetId.value.takeLast(1) }

        assertEquals(listOf("2", "1"), order)
    }

    /**
     * An unrated life-threatening report must not sort below a rated LOW one.
     * The server has not scored it yet; assuming the cheapest interpretation is
     * the expensive mistake.
     */
    @Test
    fun `an unscored life threatening report outranks a scored low one`() {
        report(1, priority = "LOW")
        report(2, priority = null, lifeThreatening = true)

        val first = coordinator().selectBatch(clock.nowMs()).first()

        assertEquals("00000000-0000-4000-8000-000000000002", first.packetId.value)
    }

    @Test
    fun `a batch is capped at the configured size`() {
        repeat(10) { report(it + 1) }

        assertEquals(4, coordinator(batchSize = 4).selectBatch(clock.nowMs()).size)
    }

    // --- outcomes ---------------------------------------------------------

    @Test
    fun `accepted packets are marked delivered and carry the server code`() {
        val r = report(1)

        val outcome = coordinator().syncOnce()

        assertEquals(1, outcome.accepted)
        val stored = store.get(r.packetId)!!
        assertEquals(DeliveryState.DELIVERED, stored.deliveryState)
        assertEquals("BLG-2026-0001", stored.emergencyCode)
        assertEquals("CRITICAL", stored.priorityLevel)
        assertTrue(stored.synced)
        assertFalse(stored.isPendingSync)
    }

    /** The server already holding it is delivery, not failure. */
    @Test
    fun `a duplicate response counts as delivered`() {
        val r = report(1)
        respond = { req ->
            SyncResponseDto(
                serverTime = Iso8601.format(clock.nowMs()),
                results = req.packets.map { PacketResultDto(it.packetId, "DUPLICATE") },
            )
        }

        val outcome = coordinator().syncOnce()

        assertEquals(1, outcome.duplicate)
        assertEquals(DeliveryState.DELIVERED, store.get(r.packetId)!!.deliveryState)
        assertTrue(store.get(r.packetId)!!.synced)
    }

    /** A TTL-expired packet can no longer be relayed but is still deliverable. */
    @Test
    fun `a ttl expired packet still syncs successfully`() {
        val r = report(1)
        respond = { req ->
            SyncResponseDto(
                serverTime = Iso8601.format(clock.nowMs()),
                results = req.packets.map {
                    PacketResultDto(it.packetId, "TTL_EXPIRED_ACCEPTED", "BLG-2026-0009")
                },
            )
        }

        assertEquals(1, coordinator().syncOnce().accepted)
        assertEquals(DeliveryState.DELIVERED, store.get(r.packetId)!!.deliveryState)
    }

    /**
     * Retrying a rejected packet forever would drain a battery in a barangay
     * that cannot spare one.
     */
    @Test
    fun `a permanently rejected packet stops being retried`() {
        val r = report(1)
        respond = { req ->
            SyncResponseDto(
                serverTime = Iso8601.format(clock.nowMs()),
                results = req.packets.map {
                    PacketResultDto(it.packetId, "INVALID_HMAC", reason = "Signature failed")
                },
            )
        }

        coordinator().syncOnce()

        val stored = store.get(r.packetId)!!
        assertEquals("Signature failed", stored.permanentFailure)
        assertFalse(stored.isPendingSync)
        assertEquals(
            DeliveryState.SAVED_LOCAL, stored.deliveryState,
            "a refused report is still only on this phone, and must not claim otherwise",
        )
    }

    @Test
    fun `a transport failure leaves everything queued`() {
        val r = report(1)
        respond = { throw RuntimeException("connection reset") }

        val outcome = coordinator().syncOnce()

        assertTrue(outcome.failed)
        assertEquals("connection reset", outcome.error)
        assertTrue(store.get(r.packetId)!!.isPendingSync)
        assertEquals(1, store.get(r.packetId)!!.attemptCount)
    }

    /** Silence about a packet is not consent. */
    @Test
    fun `a packet the server did not mention stays pending`() {
        val r = report(1)
        report(2)
        respond = { req ->
            SyncResponseDto(
                serverTime = Iso8601.format(clock.nowMs()),
                // Only the first packet is acknowledged.
                results = listOf(PacketResultDto(req.packets.first().packetId, "ACCEPTED")),
            )
        }

        coordinator().syncOnce()

        assertFalse(store.get(r.packetId)!!.isPendingSync)
        val unmentioned = store.all().single { it.packetId.value.endsWith("2") }
        assertTrue(unmentioned.isPendingSync, "an unmentioned packet must not be assumed delivered")
    }

    @Test
    fun `nothing is sent while offline`() {
        report(1)
        online = false

        val outcome = coordinator().syncOnce()

        assertTrue(outcome.failed)
        assertEquals("offline", outcome.error)
        assertTrue(sent.isEmpty(), "the API must not be called with no connectivity")
    }

    @Test
    fun `an empty queue produces no request`() {
        val outcome = coordinator().syncOnce()

        assertEquals(0, outcome.attempted)
        assertTrue(sent.isEmpty())
    }

    /** Re-running a completed sync must not resend or double-count. */
    @Test
    fun `syncing twice does not resend a delivered report`() {
        report(1)

        coordinator().syncOnce()
        val second = coordinator().syncOnce()

        assertEquals(1, sent.size, "only one request should ever have been made")
        assertEquals(0, second.attempted)
    }

    // --- backoff ----------------------------------------------------------

    @Test
    fun `a failed report waits before being retried`() {
        report(1, attempts = 1, lastAttemptAtMs = clock.nowMs())

        assertTrue(
            coordinator().selectBatch(clock.nowMs()).isEmpty(),
            "a just-failed report must not be retried immediately",
        )

        clock.advanceBy(60_000)
        assertEquals(1, coordinator().selectBatch(clock.nowMs()).size)
    }

    @Test
    fun `backoff grows with each attempt and is capped`() {
        val policy = BackoffPolicy(random = { 0.5 })
        val now = clock.nowMs()

        val first = policy.delayFor(report(1, attempts = 1, lastAttemptAtMs = now), now)
        val third = policy.delayFor(report(2, attempts = 3, lastAttemptAtMs = now), now)
        val huge = policy.delayFor(report(3, attempts = 40, lastAttemptAtMs = now), now)

        assertTrue(third > first, "delay must grow with repeated failure")
        assertTrue(huge <= 15 * 60_000, "delay must stay capped; was $huge")
    }

    /**
     * Every phone in a barangay regains signal at the same moment. Without
     * jitter they would retry in lockstep and arrive as one spike.
     */
    @Test
    fun `jitter spreads retries across devices`() {
        val now = clock.nowMs()
        val r = report(1, attempts = 3, lastAttemptAtMs = now)

        val delays = (0..20).map { i ->
            BackoffPolicy(random = { i / 20.0 }).delayFor(r, now)
        }.distinct()

        assertTrue(
            delays.size > 10,
            "retry delays should be spread, not identical; got ${delays.size} distinct values",
        )
    }

    @Test
    fun `next attempt delay is null when nothing is queued`() {
        assertNull(coordinator().nextAttemptDelayMs())

        report(1)
        assertNotNull(coordinator().nextAttemptDelayMs())
    }

    // --- request shape ----------------------------------------------------

    @Test
    fun `the request carries the device clock for drift measurement`() {
        report(1)

        coordinator().syncOnce()

        assertEquals(Iso8601.format(clock.nowMs()), sent.single().clientClock)
    }
}
