package ph.bulig.mesh.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ph.bulig.mesh.model.DeviceId

/**
 * The arithmetic a results chapter will quote.
 *
 * §24 metric 2 can only be measured on the phone — the server never learns when
 * a scan started. So these numbers have no second source to check them against,
 * which is exactly why they are tested here rather than trusted.
 */
class DistributionTest {

    @Test
    fun `an empty distribution reports nothing rather than zero`() {
        val empty = Distribution.of(emptyList())

        assertEquals(0, empty.n)
        assertNull(empty.medianMs, "no samples must not report a median of zero")
        assertNull(empty.p90Ms)
        assertTrue(!empty.hasSamples)
    }

    @Test
    fun `a single sample is its own median and extremes`() {
        val one = Distribution.of(listOf(1_400))

        assertEquals(1, one.n)
        assertEquals(1_400, one.minMs)
        assertEquals(1_400, one.medianMs)
        assertEquals(1_400, one.maxMs)
    }

    @Test
    fun `samples are ordered before percentiles are taken`() {
        val jumbled = Distribution.of(listOf(9_000, 1_000, 5_000, 3_000, 7_000))

        assertEquals(1_000, jumbled.minMs)
        assertEquals(5_000, jumbled.medianMs)
        assertEquals(9_000, jumbled.maxMs)
    }

    /**
     * Nearest-rank, so every reported percentile is a duration some run
     * actually produced. An interpolated p90 that no encounter ever took is
     * harder to defend than one that names a real measurement.
     */
    @Test
    fun `every reported percentile is a value that was actually observed`() {
        val samples = listOf(100L, 200, 300, 400, 500, 600, 700, 800, 900, 1_000)
        val d = Distribution.of(samples)

        assertTrue(samples.contains(d.medianMs), "median ${d.medianMs} was never observed")
        assertTrue(samples.contains(d.p90Ms), "p90 ${d.p90Ms} was never observed")
        assertEquals(500, d.medianMs)
        assertEquals(900, d.p90Ms)
    }

    /**
     * The shape this metric actually has: mostly fast, with a long tail. A mean
     * over it describes an encounter that never happens, which is why the
     * distribution reports median and p90 instead.
     */
    @Test
    fun `a skewed distribution reports a median unmoved by its tail`() {
        val skewed = List(9) { 1_000L } + listOf(30_000L)
        val d = Distribution.of(skewed)

        assertEquals(1_000, d.medianMs)
        assertEquals(30_000, d.maxMs)
        assertTrue(d.medianMs!! < d.maxMs!! / 10, "the outlier dragged the median")
    }

    @Test
    fun `percentiles never fall outside the sample`() {
        listOf(1, 2, 3, 7, 10, 99).forEach { size ->
            val samples = (1..size).map { it * 10L }
            val d = Distribution.of(samples)

            assertTrue(d.p90Ms!! in samples, "p90 escaped the sample at n=$size")
            assertTrue(d.medianMs!! in samples, "median escaped the sample at n=$size")
        }
    }
}

class EncounterRecorderTest {

    private val now = 1_787_802_731_000L
    private val peerA = DeviceId("phone-7C4A")
    private val peerB = DeviceId("phone-B119")

    private fun recorder() = EncounterRecorder()

    // --- discovery ---------------------------------------------------------

    @Test
    fun `discovery time is measured from the start of the scan`() {
        val r = recorder()
        r.onScanStarted(now)

        val observation = r.onPeerDiscovered(peerA, now + 2_400)

        assertNotNull(observation)
        assertEquals(2_400, observation.elapsedMs)
        assertEquals(1, r.snapshot().discovery.n)
    }

    /**
     * BLE peers re-advertise constantly. Counting every advertisement would
     * report a discovery time of zero and make the metric meaningless.
     */
    @Test
    fun `a peer re-advertising within one window is not counted again`() {
        val r = recorder()
        r.onScanStarted(now)

        assertNotNull(r.onPeerDiscovered(peerA, now + 1_000))
        assertNull(r.onPeerDiscovered(peerA, now + 1_100))
        assertNull(r.onPeerDiscovered(peerA, now + 1_200))

        assertEquals(1, r.snapshot().discovery.n)
        assertEquals(1, r.snapshot().peersDiscovered)
    }

    /**
     * The interval the metric is about is *this* window's. A peer found again
     * after a new scan starts is a fresh measurement, not a duplicate.
     */
    @Test
    fun `the same peer is measured again in a later scan window`() {
        val r = recorder()

        r.onScanStarted(now)
        r.onPeerDiscovered(peerA, now + 1_000)

        r.onScanStarted(now + 60_000)
        val second = r.onPeerDiscovered(peerA, now + 63_500)

        assertNotNull(second)
        assertEquals(3_500, second.elapsedMs)
        assertEquals(2, r.snapshot().discovery.n)
        assertEquals(1, r.snapshot().distinctPeers, "the same phone was counted as two")
    }

    /**
     * A discovery with no scan start is not a fast discovery — it is an
     * unmeasured one, and recording it as zero would silently improve the
     * headline number.
     */
    @Test
    fun `a discovery with no scan start records nothing rather than zero`() {
        val r = recorder()

        assertNull(r.onPeerDiscovered(peerA, now))
        assertEquals(0, r.snapshot().discovery.n)
    }

    @Test
    fun `a clock that moved backwards does not produce a negative interval`() {
        val r = recorder()
        r.onScanStarted(now)

        assertEquals(0, r.onPeerDiscovered(peerA, now - 5_000)!!.elapsedMs)
    }

    @Test
    fun `distinct peers and total discoveries are counted separately`() {
        val r = recorder()

        r.onScanStarted(now)
        r.onPeerDiscovered(peerA, now + 800)
        r.onPeerDiscovered(peerB, now + 1_600)

        r.onScanStarted(now + 60_000)
        r.onPeerDiscovered(peerA, now + 60_900)

        val stats = r.snapshot()
        assertEquals(3, stats.peersDiscovered)
        assertEquals(2, stats.distinctPeers)
        assertEquals(2, stats.scanWindows)
    }

    // --- encounters ---------------------------------------------------------

    @Test
    fun `an encounter records its duration and what it delivered`() {
        val r = recorder()
        r.onEncounterStarted(peerA, now)

        val observation = r.onEncounterEnded(peerA, now + 4_200, packetsDelivered = 3)

        assertNotNull(observation)
        assertEquals(4_200, observation.durationMs)
        assertEquals(3, observation.packetsDelivered)
        assertEquals(3, r.snapshot().packetsDelivered)
    }

    /**
     * People walk out of range mid-transfer constantly. A field study that
     * hides that has not measured the thing it claims to.
     */
    @Test
    fun `abandoned encounters are counted, not discarded`() {
        val r = recorder()

        r.onEncounterStarted(peerA, now)
        r.onEncounterEnded(peerA, now + 900, completed = false)

        r.onEncounterStarted(peerB, now + 2_000)
        r.onEncounterEnded(peerB, now + 8_000, packetsDelivered = 2, completed = true)

        val stats = r.snapshot()
        assertEquals(2, stats.encountersStarted)
        assertEquals(1, stats.encountersCompleted)
        assertEquals(1, stats.encountersAbandoned)
        assertEquals(2, stats.encounterDuration.n, "an abandoned encounter still took time")
    }

    @Test
    fun `ending an encounter that never started records nothing`() {
        assertNull(recorder().onEncounterEnded(peerA, now))
    }

    @Test
    fun `two peers connected at once do not confuse each other's durations`() {
        val r = recorder()

        r.onEncounterStarted(peerA, now)
        r.onEncounterStarted(peerB, now + 1_000)

        assertEquals(9_000, r.onEncounterEnded(peerB, now + 10_000)!!.durationMs)
        assertEquals(12_000, r.onEncounterEnded(peerA, now + 12_000)!!.durationMs)
    }

    // --- bounds and lifecycle ----------------------------------------------

    /** A phone left relaying for hours must not grow a sample list forever. */
    @Test
    fun `sample retention is bounded and keeps the most recent`() {
        val r = EncounterRecorder(maxSamples = 3)

        (1..6).forEach { i ->
            r.onScanStarted(now + i * 60_000L)
            r.onPeerDiscovered(DeviceId("peer-$i"), now + i * 60_000L + i * 1_000L)
        }

        val discovery = r.snapshot().discovery
        assertEquals(3, discovery.n)
        assertEquals(4_000, discovery.minMs, "the oldest samples were not the ones dropped")
        assertEquals(6_000, discovery.maxMs)
    }

    /** Between field-test configurations, so one run's numbers do not leak into the next. */
    @Test
    fun `reset clears every counter`() {
        val r = recorder()
        r.onScanStarted(now)
        r.onPeerDiscovered(peerA, now + 1_000)
        r.onEncounterStarted(peerA, now + 1_000)
        r.onEncounterEnded(peerA, now + 5_000, packetsDelivered = 4)

        r.reset()

        val stats = r.snapshot()
        assertEquals(0, stats.scanWindows)
        assertEquals(0, stats.peersDiscovered)
        assertEquals(0, stats.distinctPeers)
        assertEquals(0, stats.encountersStarted)
        assertEquals(0, stats.packetsDelivered)
        assertTrue(!stats.discovery.hasSamples)
    }

    @Test
    fun `a recorder that has seen nothing reports nothing rather than zeroes`() {
        val stats = recorder().snapshot()

        assertNull(stats.discovery.medianMs)
        assertNull(stats.encounterDuration.medianMs)
        assertEquals(0, stats.encountersAbandoned)
    }

    /** The shape a field run produces, end to end. */
    @Test
    fun `a full field run produces a reportable distribution`() {
        val r = recorder()

        listOf(1_200L, 800, 3_400, 2_100, 900, 15_000, 1_100, 2_800, 1_500, 1_000)
            .forEachIndexed { i, delay ->
                val windowStart = now + i * 60_000L
                r.onScanStarted(windowStart)
                r.onPeerDiscovered(DeviceId("peer-$i"), windowStart + delay)
            }

        val d = r.snapshot().discovery

        assertEquals(10, d.n)
        assertEquals(800, d.minMs)
        assertEquals(15_000, d.maxMs)
        assertEquals(1_200, d.medianMs)
        assertEquals(3_400, d.p90Ms)
    }
}
