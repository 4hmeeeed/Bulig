package ph.bulig.mesh.metrics

import ph.bulig.mesh.model.DeviceId

/**
 * One measured discovery: scan started, peer appeared.
 *
 * This is §24 metric 2, and it is the number a panel is most likely to ask for
 * after delivery rate — "how long before two phones find each other?" is the
 * question that decides whether a mesh is usable by people walking past one
 * another rather than standing still.
 */
data class DiscoveryObservation(
    val peer: DeviceId,
    val scanStartedAtMs: Long,
    val discoveredAtMs: Long,
) {
    val elapsedMs: Long get() = (discoveredAtMs - scanStartedAtMs).coerceAtLeast(0)
}

/** One completed encounter, from connect to disconnect. */
data class EncounterObservation(
    val peer: DeviceId,
    val durationMs: Long,
    val packetsDelivered: Int,
    val completed: Boolean,
)

/**
 * A distribution reported honestly.
 *
 * Median and p90 rather than a mean alone: discovery times are heavily skewed —
 * most encounters resolve in a second or two and a few take twenty — and a mean
 * over that shape describes an encounter that never happens.
 */
data class Distribution(
    val n: Int,
    val minMs: Long?,
    val medianMs: Long?,
    val p90Ms: Long?,
    val maxMs: Long?,
) {
    val hasSamples: Boolean get() = n > 0

    companion object {
        val EMPTY = Distribution(0, null, null, null, null)

        fun of(samples: List<Long>): Distribution {
            if (samples.isEmpty()) return EMPTY

            val sorted = samples.sorted()

            return Distribution(
                n = sorted.size,
                minMs = sorted.first(),
                medianMs = percentile(sorted, 0.50),
                p90Ms = percentile(sorted, 0.90),
                maxMs = sorted.last(),
            )
        }

        /**
         * Nearest-rank on a pre-sorted list.
         *
         * Chosen over interpolation because these are observed durations, and a
         * reported p90 that no run actually produced is harder to defend than
         * one that names a real measurement.
         */
        internal fun percentile(sorted: List<Long>, fraction: Double): Long? {
            if (sorted.isEmpty()) return null

            val rank = Math.ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}

/** Everything the evaluation needs from the radio, as one object. */
data class EncounterStats(
    val discovery: Distribution,
    val encounterDuration: Distribution,
    val scanWindows: Int,
    val peersDiscovered: Int,
    val distinctPeers: Int,
    val encountersStarted: Int,
    val encountersCompleted: Int,
    val packetsDelivered: Int,
) {
    /**
     * Encounters that ended before the transfer finished.
     *
     * Expected to be high, and reporting it is the point: people walk out of
     * range mid-transfer constantly, and a field study that hides that has not
     * measured the thing it claims to.
     */
    val encountersAbandoned: Int get() = encountersStarted - encountersCompleted
}

/**
 * Measures what the radio actually does, so the evaluation is not a stopwatch
 * exercise.
 *
 * `docs/10-testing-plan.md` §10.5 lists ten operational metrics. Six of them
 * come from the server's own tables because the server sees every packet. Two
 * come from tools outside the app entirely (Battery Historian, browser
 * instrumentation). **Metric 2 — device discovery time — can only be measured
 * on the phone**, because the server never learns when a scan started, and until
 * this class existed it was measured nowhere at all.
 *
 * Pure Kotlin with an injected clock, so the percentile arithmetic that a
 * results chapter will quote is tested rather than trusted.
 *
 * @see docs/10-testing-plan.md 10.4-10.5
 */
class EncounterRecorder(
    /** Bounded so a phone left relaying for hours does not grow without limit. */
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES,
) {

    private var scanStartedAtMs: Long? = null
    private var scanWindows = 0

    private val discoveryMs = ArrayDeque<Long>()
    private val encounterMs = ArrayDeque<Long>()

    /** Peers already discovered in this scan window, so re-advertisements do not re-count. */
    private val discoveredThisWindow = mutableSetOf<DeviceId>()
    private val everSeen = mutableSetOf<DeviceId>()

    private val openEncounters = mutableMapOf<DeviceId, Long>()

    private var peersDiscovered = 0
    private var encountersStarted = 0
    private var encountersCompleted = 0
    private var packetsDelivered = 0

    fun onScanStarted(nowMs: Long) {
        scanStartedAtMs = nowMs
        scanWindows++
        // A peer found in the previous window is a fresh discovery in this one:
        // that is exactly the interval the metric is about.
        discoveredThisWindow.clear()
    }

    /**
     * Returns the measurement, or null when there is nothing meaningful to record.
     *
     * Null for a peer already seen in this window (BLE re-advertises constantly,
     * and counting every advertisement would report a discovery time of zero),
     * and null when no scan was ever started — a measurement with no start is
     * not a fast discovery, it is an unmeasured one.
     */
    fun onPeerDiscovered(peer: DeviceId, nowMs: Long): DiscoveryObservation? {
        val startedAt = scanStartedAtMs ?: return null
        if (!discoveredThisWindow.add(peer)) return null

        peersDiscovered++
        everSeen += peer

        return DiscoveryObservation(peer, startedAt, nowMs).also {
            record(discoveryMs, it.elapsedMs)
        }
    }

    fun onEncounterStarted(peer: DeviceId, nowMs: Long) {
        // A reconnect to a peer already open replaces the old start rather than
        // leaking it; the duration reported is for the encounter that ended.
        openEncounters[peer] = nowMs
        encountersStarted++
    }

    /**
     * @param completed whether the session finished its work, as opposed to the
     *   peer walking out of range mid-transfer.
     */
    fun onEncounterEnded(
        peer: DeviceId,
        nowMs: Long,
        packetsDelivered: Int = 0,
        completed: Boolean = true,
    ): EncounterObservation? {
        val startedAt = openEncounters.remove(peer) ?: return null

        val duration = (nowMs - startedAt).coerceAtLeast(0)
        record(encounterMs, duration)

        this.packetsDelivered += packetsDelivered
        if (completed) encountersCompleted++

        return EncounterObservation(peer, duration, packetsDelivered, completed)
    }

    fun snapshot(): EncounterStats = EncounterStats(
        discovery = Distribution.of(discoveryMs.toList()),
        encounterDuration = Distribution.of(encounterMs.toList()),
        scanWindows = scanWindows,
        peersDiscovered = peersDiscovered,
        distinctPeers = everSeen.size,
        encountersStarted = encountersStarted,
        encountersCompleted = encountersCompleted,
        packetsDelivered = packetsDelivered,
    )

    /** Between field runs, so one configuration's numbers do not leak into the next. */
    fun reset() {
        scanStartedAtMs = null
        scanWindows = 0
        discoveryMs.clear()
        encounterMs.clear()
        discoveredThisWindow.clear()
        everSeen.clear()
        openEncounters.clear()
        peersDiscovered = 0
        encountersStarted = 0
        encountersCompleted = 0
        packetsDelivered = 0
    }

    /** Oldest sample drops out first, so a long session reports its recent behaviour. */
    private fun record(into: ArrayDeque<Long>, value: Long) {
        into.addLast(value)
        while (into.size > maxSamples) into.removeFirst()
    }

    companion object {
        const val DEFAULT_MAX_SAMPLES = 500
    }
}
