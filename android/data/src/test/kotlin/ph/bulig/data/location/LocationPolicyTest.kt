package ph.bulig.data.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocationPolicyTest {

    private val now = 1_787_802_731_000L

    private fun fix(
        accuracyM: Double = 20.0,
        ageMs: Long = 0,
        lat: Double = 11.2447321,
        lng: Double = 125.0048119,
    ) = LocationFix(
        latitude = lat,
        longitude = lng,
        accuracyM = accuracyM,
        capturedAtMs = now - ageMs,
        provider = "gps",
    )

    // --- quality ----------------------------------------------------------

    @Test
    fun `accuracy bands match the documented thresholds`() {
        assertEquals(FixQuality.GOOD, LocationPolicy.quality(fix(accuracyM = 12.0)))
        assertEquals(FixQuality.GOOD, LocationPolicy.quality(fix(accuracyM = 50.0)))
        assertEquals(FixQuality.APPROXIMATE, LocationPolicy.quality(fix(accuracyM = 51.0)))
        assertEquals(FixQuality.APPROXIMATE, LocationPolicy.quality(fix(accuracyM = 200.0)))
        assertEquals(FixQuality.POOR, LocationPolicy.quality(fix(accuracyM = 201.0)))
    }

    // --- the rule that matters --------------------------------------------

    /**
     * A resident on a roof indoors may never get a good fix. An app that
     * insisted on one would fail exactly the person it exists for.
     */
    @Test
    fun `every quality can still send the report`() {
        listOf(10.0, 120.0, 900.0).forEach { accuracy ->
            val state = LocationPolicy.build(fix(accuracyM = accuracy), false, now)
            assertTrue(state.canContinue, "a ${accuracy}m fix blocked the report")
        }
    }

    @Test
    fun `no fix at all can still send the report`() {
        val state = LocationPolicy.build(null, isSearching = false, nowMs = now)

        assertTrue(state.canContinue)
        assertTrue(state.detail.contains("You can still send this report"))
        assertTrue(!state.hasFix)
    }

    @Test
    fun `a poor fix is described honestly rather than hidden`() {
        val state = LocationPolicy.build(fix(accuracyM = 800.0), false, now)

        assertEquals(FixQuality.POOR, state.quality)
        assertTrue(state.detail.contains("Still worth sending"))
        assertTrue(state.detail.contains("landmark"))
    }

    @Test
    fun `an approximate fix says what it can and cannot locate`() {
        val state = LocationPolicy.build(fix(accuracyM = 150.0), false, now)

        assertTrue(state.detail.contains("purok"))
        assertTrue(state.detail.contains("not your door"))
    }

    @Test
    fun `a good fix states its accuracy plainly`() {
        val state = LocationPolicy.build(fix(accuracyM = 18.0), false, now)

        assertEquals("Location found", state.headline)
        assertTrue(state.detail.contains("18 metres"))
    }

    // --- staleness --------------------------------------------------------

    /**
     * Somebody walking to higher ground moves a long way in two minutes, and a
     * team sent to where they were is worse than one sent to a wider circle
     * around where they are.
     */
    @Test
    fun `a fix older than two minutes is stale`() {
        assertTrue(!LocationPolicy.isStale(fix(ageMs = 119_000), now))
        assertTrue(LocationPolicy.isStale(fix(ageMs = 121_000), now))
    }

    @Test
    fun `a stale fix warns that the barangay may look in the wrong place`() {
        val state = LocationPolicy.build(fix(ageMs = 300_000), false, now)

        assertEquals("Location may be out of date", state.headline)
        assertTrue(state.detail.contains("wrong place"))
        assertTrue(state.canContinue, "a stale fix must not block the report")
    }

    // --- replacement ------------------------------------------------------

    @Test
    fun `the first fix is always taken`() {
        assertTrue(LocationPolicy.shouldReplace(null, fix(accuracyM = 900.0), now))
    }

    @Test
    fun `a more accurate fix replaces a less accurate one`() {
        val held = fix(accuracyM = 120.0)
        val better = fix(accuracyM = 30.0)

        assertTrue(LocationPolicy.shouldReplace(held, better, now))
    }

    @Test
    fun `a less accurate fix does not replace a fresh better one`() {
        val held = fix(accuracyM = 20.0)
        val worse = fix(accuracyM = 200.0)

        assertTrue(!LocationPolicy.shouldReplace(held, worse, now))
    }

    /**
     * The case worth encoding: a vague fix from where the resident is *now*
     * beats a precise one from where they were two minutes ago.
     */
    @Test
    fun `a vague new fix replaces a precise stale one`() {
        val staleButPrecise = fix(accuracyM = 8.0, ageMs = 400_000)
        val freshButVague = fix(accuracyM = 180.0, ageMs = 0)

        assertTrue(
            LocationPolicy.shouldReplace(staleButPrecise, freshButVague, now),
            "the app kept a fix from where the resident used to be",
        )
    }

    @Test
    fun `an older fix arriving late does not overwrite a newer one`() {
        val current = fix(accuracyM = 60.0, ageMs = 0)
        val late = fix(accuracyM = 10.0, ageMs = 30_000)

        assertTrue(
            !LocationPolicy.shouldReplace(current, late, now),
            "a late-arriving older fix overwrote a newer one",
        )
    }

    // --- searching --------------------------------------------------------

    @Test
    fun `searching says so rather than showing an empty state`() {
        val state = LocationPolicy.build(null, isSearching = true, nowMs = now)

        assertEquals("Finding your location…", state.headline)
        assertTrue(state.detail.contains("take a moment indoors"))
        assertTrue(state.canContinue, "a resident must not have to wait for GPS")
    }

    /** The manual pin is offered exactly when the automatic fix is not good enough. */
    @Test
    fun `a manual pin is offered whenever the fix is not good`() {
        assertTrue(LocationPolicy.build(null, false, now).showsManualPinOption)
        assertTrue(LocationPolicy.build(fix(accuracyM = 300.0), false, now).showsManualPinOption)
        assertTrue(!LocationPolicy.build(fix(accuracyM = 15.0), false, now).showsManualPinOption)
    }
}
