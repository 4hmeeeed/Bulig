package ph.bulig.data.location

/** One position fix, from whatever source produced it. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double,
    val capturedAtMs: Long,
    val provider: String,
)

/** How much a fix can be trusted, in the terms the screen uses. */
enum class FixQuality {
    /** Good enough to send a rescue team to. */
    GOOD,

    /** Usable, but the resident should be told it is approximate. */
    APPROXIMATE,

    /** Too vague to act on alone. Still sent — a wide circle beats nothing. */
    POOR,
}

/**
 * What the location step shows and sends.
 *
 * The rule this encodes is the one that matters: **a poor fix is never a reason
 * to refuse a report.** A resident on a roof indoors may never get a good fix,
 * and an app that insisted on one would fail exactly the person it exists for.
 * So every quality below sends, and the only difference is what the screen says
 * about it.
 */
data class LocationUiState(
    val fix: LocationFix?,
    val quality: FixQuality?,
    val isSearching: Boolean,
    val headline: String,
    val detail: String,
    val canContinue: Boolean,
    val showsManualPinOption: Boolean,
) {
    val hasFix: Boolean get() = fix != null
}

object LocationPolicy {

    /**
     * Thresholds in metres.
     *
     * 50 m is roughly a house and its neighbours — close enough that a team
     * arriving can ask. 200 m is a purok, which still narrows a search
     * usefully. Beyond that a fix mostly says which barangay you are in.
     *
     * TO BE VALIDATED against real fixes in the pilot barangay: dense
     * construction and tree cover will move these.
     */
    const val GOOD_ACCURACY_M = 50.0
    const val APPROXIMATE_ACCURACY_M = 200.0

    /**
     * How old a fix may be before it is treated as stale.
     *
     * Two minutes. Somebody walking to higher ground moves a long way in that
     * time, and a rescue team sent to where they were is worse than one sent to
     * a wider circle around where they are.
     */
    const val MAX_FIX_AGE_MS = 120_000L

    fun quality(fix: LocationFix): FixQuality = when {
        fix.accuracyM <= GOOD_ACCURACY_M -> FixQuality.GOOD
        fix.accuracyM <= APPROXIMATE_ACCURACY_M -> FixQuality.APPROXIMATE
        else -> FixQuality.POOR
    }

    fun isStale(fix: LocationFix, nowMs: Long): Boolean =
        nowMs - fix.capturedAtMs > MAX_FIX_AGE_MS

    /**
     * Whether a newly arrived fix should replace the one already held.
     *
     * Accepts a *less* accurate fix when the held one has gone stale, because a
     * vague fix from where the resident is now beats a precise one from where
     * they were two minutes ago.
     */
    fun shouldReplace(current: LocationFix?, candidate: LocationFix, nowMs: Long): Boolean {
        if (current == null) return true
        if (isStale(current, nowMs)) return true
        if (candidate.capturedAtMs < current.capturedAtMs) return false

        return candidate.accuracyM < current.accuracyM
    }

    fun build(fix: LocationFix?, isSearching: Boolean, nowMs: Long): LocationUiState {
        val quality = fix?.let { quality(it) }
        val stale = fix != null && isStale(fix, nowMs)

        return LocationUiState(
            fix = fix,
            quality = quality,
            isSearching = isSearching,
            headline = headline(quality, stale, isSearching),
            detail = detail(fix, quality, stale, isSearching),
            // Always. A report with no location still reaches the barangay, and
            // refusing to file one would be the worse failure.
            canContinue = true,
            showsManualPinOption = fix == null || quality != FixQuality.GOOD,
        )
    }

    private fun headline(quality: FixQuality?, stale: Boolean, searching: Boolean): String = when {
        quality == null && searching -> "Finding your location…"
        quality == null -> "No location yet"
        stale -> "Location may be out of date"
        quality == FixQuality.GOOD -> "Location found"
        else -> "Approximate location"
    }

    private fun detail(
        fix: LocationFix?,
        quality: FixQuality?,
        stale: Boolean,
        searching: Boolean,
    ): String = when {
        fix == null && searching ->
            "This can take a moment indoors. You can continue without it."

        fix == null ->
            "Your phone has not found a GPS fix. You can still send this report — " +
                "the barangay will see it without a pin on the map."

        stale ->
            "This fix is a few minutes old. If you have moved, the barangay may " +
                "look in the wrong place."

        quality == FixQuality.GOOD ->
            "Accurate to about ${fix.accuracyM.toInt()} metres."

        quality == FixQuality.APPROXIMATE ->
            "Accurate to about ${fix.accuracyM.toInt()} metres — close enough to " +
                "find your purok, not your door."

        else ->
            "Only accurate to about ${fix.accuracyM.toInt()} metres. Still worth " +
                "sending, but add a landmark in the description if you can."
    }
}
