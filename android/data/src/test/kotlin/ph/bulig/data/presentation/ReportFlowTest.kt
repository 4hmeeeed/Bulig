package ph.bulig.data.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ph.bulig.mesh.priority.PriorityLevel

/**
 * The four-step report flow.
 *
 * These rules decide what a frightened person is allowed to do and what they are
 * told at each step, so they are tested here rather than left inside Compose
 * where nothing could exercise them.
 *
 * @see docs/design/HANDOFF.md — artboards 02 to 06
 */
class ReportFlowTest {

    private val now = 1_787_802_731_000L

    private val types = listOf(
        EmergencyTypeOption("MEDICAL", "Medical", "Emerhensya Medikal", "medical_services", 35, true),
        EmergencyTypeOption("FLOOD", "Flood", "Baha", "flood", 30, false),
        EmergencyTypeOption("INFRA", "Infrastructure", "Nadaot nga pasilidad", "construction", 15, false),
    )

    private fun start() = ReportFlowState(types = types)

    private fun atReview(): ReportFlowState {
        var s = ReportFlowReducer.selectType(start(), "FLOOD")
        s = ReportFlowReducer.next(s, now)          // -> DETAILS
        s = ReportFlowReducer.next(s, now)          // -> LOCATION
        return ReportFlowReducer.next(s, now)       // -> REVIEW
    }

    // --- progression ------------------------------------------------------

    @Test
    fun `the flow starts on the type picker and cannot advance without one`() {
        val state = start()

        assertEquals(ReportStep.TYPE, state.step)
        assertFalse(state.canContinue, "a report needs a type before anything else")
        assertEquals(state, ReportFlowReducer.next(state, now), "next must be a no-op")
    }

    @Test
    fun `choosing a type unlocks the flow`() {
        val state = ReportFlowReducer.selectType(start(), "FLOOD")

        assertTrue(state.canContinue)
        assertEquals("Flood", state.selectedType?.labelEn)
        assertEquals("Baha", state.selectedType?.labelWar)
    }

    @Test
    fun `step labels count from one to four`() {
        assertEquals("1 / 4", ReportStep.TYPE.label)
        assertEquals("4 / 4", ReportStep.REVIEW.label)
        assertEquals("", ReportStep.SUBMITTED.label, "the confirmation is outside the count")
    }

    /**
     * Everything after the type is optional. A resident in a flood must never be
     * stopped by a form.
     */
    @Test
    fun `a report with only a type reaches review`() {
        val state = atReview()

        assertEquals(ReportStep.REVIEW, state.step)
        assertTrue(state.canContinue)
        assertNull(state.draft.description)
        assertFalse(state.hasCoordinates)
    }

    @Test
    fun `back walks the flow in reverse but not out of it`() {
        val review = atReview()

        val location = ReportFlowReducer.back(review)
        assertEquals(ReportStep.LOCATION, location.step)

        val type = ReportFlowReducer.back(ReportFlowReducer.back(location))
        assertEquals(ReportStep.TYPE, type.step)
        assertEquals(type, ReportFlowReducer.back(type), "there is nothing before the first step")
    }

    /** Going back from the confirmation would invite filing the same report twice. */
    @Test
    fun `the confirmation screen cannot be reversed into`() {
        val submitted = ReportFlowReducer.submitted(atReview(), "BLG-2026-0417")

        assertEquals(submitted, ReportFlowReducer.back(submitted))
        assertFalse(submitted.canContinue)
    }

    @Test
    fun `change links jump from review to the owning step`() {
        val review = atReview()

        assertEquals(ReportStep.DETAILS, ReportFlowReducer.jumpTo(review, ReportStep.DETAILS).step)
        assertEquals(ReportStep.LOCATION, ReportFlowReducer.jumpTo(review, ReportStep.LOCATION).step)
    }

    @Test
    fun `change links do nothing outside review`() {
        val typeStep = ReportFlowReducer.selectType(start(), "FLOOD")

        assertEquals(typeStep, ReportFlowReducer.jumpTo(typeStep, ReportStep.LOCATION))
    }

    // --- steppers ---------------------------------------------------------

    /**
     * The minus button is disabled at the floor, never hidden — hiding it would
     * reflow the row under a thumb that is already moving.
     */
    @Test
    fun `the minus control is disabled at its floor rather than removed`() {
        val state = ReportFlowReducer.selectType(start(), "FLOOD")

        val affected = state.steppers.first { it.labelEn == "People affected" }
        val children = state.steppers.first { it.labelEn == "Children" }

        assertEquals(1, affected.value)
        assertFalse(affected.canDecrement, "at least one person is affected by definition")
        assertEquals(0, children.value)
        assertFalse(children.canDecrement)
        assertTrue(children.canIncrement)
    }

    @Test
    fun `counts clamp instead of going out of range`() {
        var state = ReportFlowReducer.selectType(start(), "FLOOD")

        state = ReportFlowReducer.adjust(state, CountField.AFFECTED, -5)
        assertEquals(1, state.draft.affectedCount, "affected cannot drop below one")

        state = ReportFlowReducer.adjust(state, CountField.CHILDREN, -3)
        assertEquals(0, state.draft.childrenCount)

        state = ReportFlowReducer.adjust(state, CountField.ELDERLY, 2)
        assertEquals(2, state.draft.elderlyCount)
    }

    @Test
    fun `steppers carry bilingual labels`() {
        val steppers = ReportFlowReducer.selectType(start(), "FLOOD").steppers

        assertEquals("Pira ka tawo", steppers[0].labelWar)
        assertEquals("Kabataan", steppers[1].labelWar)
        assertEquals("Lagas", steppers[2].labelWar)
        assertEquals("Diri makalakat nga usa", steppers[3].labelWar)
    }

    @Test
    fun `a blank description is stored as absent rather than empty`() {
        var state = ReportFlowReducer.selectType(start(), "FLOOD")

        state = ReportFlowReducer.setDescription(state, "   ")
        assertNull(state.draft.description)

        state = ReportFlowReducer.setDescription(state, "Tubig abot na sa hita.")
        assertEquals("Tubig abot na sa hita.", state.draft.description)
    }

    // --- location ---------------------------------------------------------

    @Test
    fun `a gps fix is recorded with its accuracy`() {
        var state = ReportFlowReducer.selectType(start(), "FLOOD")
        state = ReportFlowReducer.setLocation(
            state, 11.24186, 125.00417, 38.0, "gps", now,
        )

        assertTrue(state.hasCoordinates)
        assertEquals(38.0, state.locationAccuracyM)
        assertFalse(state.pinAdjustedByHand)
    }

    @Test
    fun `a hand-adjusted pin is recorded as such`() {
        var state = ReportFlowReducer.selectType(start(), "FLOOD")
        state = ReportFlowReducer.setLocation(
            state, 11.2, 125.0, null, "manual", now, byHand = true,
        )

        assertTrue(state.pinAdjustedByHand)
        assertEquals("manual", state.draft.locationProvider)
    }

    /** A report without coordinates is better than no report. */
    @Test
    fun `a failed gps fix does not block the flow`() {
        var state = ReportFlowReducer.selectType(start(), "FLOOD")
        state = ReportFlowReducer.next(state, now)
        state = ReportFlowReducer.next(state, now)
        state = ReportFlowReducer.setPurok(state, "Purok 4, Barangay 88")

        assertFalse(state.hasCoordinates)
        assertTrue(state.canContinue, "only the purok is needed to keep going")
        assertEquals("Purok 4, Barangay 88", state.purok)
    }

    // --- priority ---------------------------------------------------------

    /** Scored on the device, offline. A spinner here would be a network dependency. */
    @Test
    fun `priority is computed when review is reached`() {
        var state = ReportFlowReducer.selectType(start(), "MEDICAL")
        state = ReportFlowReducer.next(state, now)

        assertNull(state.priority, "not yet — the resident has not finished answering")

        state = ReportFlowReducer.adjust(state, CountField.AFFECTED, 3)
        state = ReportFlowReducer.adjust(state, CountField.ELDERLY, 2)
        state = ReportFlowReducer.adjust(state, CountField.MOBILITY_LIMITED, 1)
        state = ReportFlowReducer.setLifeThreatening(state, true)
        state = ReportFlowReducer.next(state, now)   // -> LOCATION
        state = ReportFlowReducer.next(state, now)   // -> REVIEW

        val priority = state.priority
        assertNotNull(priority)
        assertEquals(PriorityLevel.CRITICAL, priority.level)
    }

    @Test
    fun `review shows the reasons that produced the priority`() {
        var state = ReportFlowReducer.selectType(start(), "FLOOD")
        state = ReportFlowReducer.next(state, now)
        state = ReportFlowReducer.adjust(state, CountField.AFFECTED, 4)
        state = ReportFlowReducer.adjust(state, CountField.CHILDREN, 2)
        state = ReportFlowReducer.setLifeThreatening(state, true)
        state = ReportFlowReducer.next(state, now)
        state = ReportFlowReducer.next(state, now)

        val reasons = state.priority!!.reasons()

        assertEquals("You marked this life-threatening", reasons.first())
        assertTrue(reasons.any { it == "2 children affected" }, "got $reasons")
    }

    @Test
    fun `a low priority report shows a level with no padding reasons`() {
        var state = ReportFlowReducer.selectType(start(), "INFRA")
        state = ReportFlowReducer.next(state, now)
        state = ReportFlowReducer.next(state, now)
        state = ReportFlowReducer.next(state, now)

        assertEquals(PriorityLevel.LOW, state.priority!!.level)
        assertTrue(state.priority!!.reasons().isEmpty())
    }

    // --- wording ----------------------------------------------------------

    /** Nothing has been sent, and the footer says so on every step but the last. */
    @Test
    fun `the footer promises nothing until the report is confirmed`() {
        val details = ReportFlowReducer.next(ReportFlowReducer.selectType(start(), "FLOOD"), now)

        assertTrue(details.footerNote.startsWith("Nothing is sent yet"))
        assertTrue(
            atReview().footerNote.startsWith("Saved instantly"),
            "the final button saves; it does not promise delivery",
        )
    }

    @Test
    fun `offline is stated matter of factly not as an error`() {
        val offline = ReportFlowReducer.selectType(start(), "FLOOD")
        val online = offline.copy(isOnline = true)

        assertTrue(offline.connectivityNote.contains("saved on your phone"))
        assertFalse(
            offline.connectivityNote.contains("error", ignoreCase = true),
            "offline is the normal case for this app",
        )
        assertTrue(online.connectivityNote.contains("You have signal"))
    }

    @Test
    fun `the emergency code appears only after the report is committed`() {
        val review = atReview()
        assertNull(review.emergencyCode)

        val submitted = ReportFlowReducer.submitted(review, "BLG-2026-0417")

        assertEquals(ReportStep.SUBMITTED, submitted.step)
        assertEquals("BLG-2026-0417", submitted.emergencyCode)
    }

    /**
     * A device with no signal has no server-assigned code yet, and the screen
     * must cope rather than showing a blank where an identifier should be.
     */
    @Test
    fun `submitting offline yields no code and that is not a failure`() {
        val submitted = ReportFlowReducer.submitted(atReview(), null)

        assertEquals(ReportStep.SUBMITTED, submitted.step)
        assertNull(submitted.emergencyCode)
    }
}
