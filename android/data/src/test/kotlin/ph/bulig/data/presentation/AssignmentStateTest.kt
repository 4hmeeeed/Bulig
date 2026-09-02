package ph.bulig.data.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ph.bulig.mesh.priority.PriorityLevel

/** Artboards 10, 11 and 12 — the responder's side. */
class AssignmentListStateTest {

    private val now = 1_787_802_731_000L
    private val minute = 60_000L

    private fun assignment(
        code: String,
        level: PriorityLevel = PriorityLevel.MODERATE,
        filedMinutesAgo: Long = 10,
        receivedMinutesAgo: Long = filedMinutesAgo,
        hops: Int = 0,
        status: ResponderStatus = ResponderStatus.ASSIGNED,
    ) = Assignment(
        emergencyCode = code,
        typeCode = "FLOOD",
        filedAtMs = now - filedMinutesAgo * minute,
        receivedAtMs = now - receivedMinutesAgo * minute,
        status = status,
        priorityLevel = level,
        hopCount = hops,
        affectedCount = 5,
    )

    private fun build(vararg assignments: Assignment) = AssignmentListStateFactory.build(
        responderName = "Tanod R. Cinco",
        zone = "Zone 2",
        assignments = assignments.toList(),
        nowMs = now,
    )

    // --- the ordering rule -------------------------------------------------

    /**
     * Strict priority order. The design says never user-reorderable, and the
     * ordering a barangay has to defend afterwards must be the one the rules
     * produced — not one a responder under pressure dragged into place.
     */
    @Test
    fun `assignments are ordered by priority before anything else`() {
        val state = build(
            assignment("LOW-1", PriorityLevel.LOW, filedMinutesAgo = 200),
            assignment("CRIT-1", PriorityLevel.CRITICAL, filedMinutesAgo = 2),
            assignment("MOD-1", PriorityLevel.MODERATE, filedMinutesAgo = 100),
            assignment("HIGH-1", PriorityLevel.HIGH, filedMinutesAgo = 50),
        )

        assertEquals(
            listOf("CRIT-1", "HIGH-1", "MOD-1", "LOW-1"),
            state.rows.map { it.emergencyCode },
        )
    }

    @Test
    fun `equal priorities put the oldest first`() {
        val state = build(
            assignment("NEW", PriorityLevel.HIGH, filedMinutesAgo = 5),
            assignment("OLD", PriorityLevel.HIGH, filedMinutesAgo = 90),
        )

        assertEquals(listOf("OLD", "NEW"), state.rows.map { it.emergencyCode })
    }

    @Test
    fun `only the top card is expanded with its action`() {
        val state = build(
            assignment("A", PriorityLevel.CRITICAL),
            assignment("B", PriorityLevel.HIGH),
            assignment("C", PriorityLevel.LOW),
        )

        assertTrue(state.rows.first().isExpanded)
        assertTrue(state.rows.drop(1).none { it.isExpanded })
    }

    // --- the age rule ------------------------------------------------------

    /**
     * The rule the footnote exists to explain, and the one a mesh makes
     * necessary: a CRITICAL that took twenty minutes to cross three phones must
     * not look like it happened two minutes ago.
     */
    @Test
    fun `age is measured from filing, not from when the responder received it`() {
        val delayed = assignment(
            "MESH-1",
            PriorityLevel.CRITICAL,
            filedMinutesAgo = 22,
            receivedMinutesAgo = 2,
            hops = 3,
        )

        val row = build(delayed).rows.single()

        assertEquals(22 * minute, row.ageMs)
        assertEquals("22 min ago", row.ageLabel)
    }

    @Test
    fun `the footnote explaining mesh age is always present`() {
        val state = build(assignment("A"))

        assertTrue(state.footnote.contains("when the resident filed it"))
        assertTrue(state.footnote.contains("not when you received it"))
    }

    @Test
    fun `age reads naturally across minutes, hours and days`() {
        fun label(ms: Long) = AssignmentListStateFactory.ageLabel(ms)

        assertEquals("just now", label(20_000))
        assertEquals("1 min ago", label(minute))
        assertEquals("45 min ago", label(45 * minute))
        assertEquals("1 hour ago", label(60 * minute))
        assertEquals("5 hours ago", label(300 * minute))
        assertEquals("2 days ago", label(2 * 24 * 60 * minute))
    }

    /** A clock that drifted backwards must not produce a negative age. */
    @Test
    fun `an assignment filed in the future does not read as negative age`() {
        val future = assignment("FUTURE").copy(filedAtMs = now + 5 * minute)

        assertEquals(0L, build(future).rows.single().ageMs)
    }

    // --- the card contents -------------------------------------------------

    @Test
    fun `the affected summary names what changes what a responder brings`() {
        val a = assignment("A").copy(
            affectedCount = 5, childrenCount = 2, elderlyCount = 1, isLifeThreatening = true,
        )

        assertEquals(
            "5 affected · 2 children · 1 elderly · life-threatening",
            AssignmentListStateFactory.affectedSummary(a),
        )
    }

    @Test
    fun `distance and purok appear together when both are known`() {
        val a = assignment("A").copy(distanceM = 420, purok = "Purok 4")

        assertEquals("420 m · Purok 4", build(a).rows.single().distanceLabel)
    }

    @Test
    fun `an assignment with no location shows no distance rather than a guess`() {
        assertNull(build(assignment("A")).rows.single().distanceLabel)
    }

    @Test
    fun `hop count is shown only when the report actually crossed the mesh`() {
        assertEquals("via 3 hops", build(assignment("A", hops = 3)).rows.single().hopLabel)
        assertEquals("via 1 hop", build(assignment("B", hops = 1)).rows.single().hopLabel)
        assertNull(build(assignment("C", hops = 0)).rows.single().hopLabel)
    }

    @Test
    fun `a status chip appears only once the responder has acted`() {
        assertNull(build(assignment("A")).rows.single().statusChip)
        assertEquals(
            "EN ROUTE",
            build(assignment("B", status = ResponderStatus.EN_ROUTE)).rows.single().statusChip,
        )
    }

    @Test
    fun `the syncing banner counts what is still owed to the server`() {
        val state = AssignmentListStateFactory.build(
            responderName = "R", zone = null,
            assignments = listOf(assignment("A")), nowMs = now, pendingUploads = 3,
        )

        assertEquals("Uploading 3 pending reports…", state.banner)
    }

    @Test
    fun `no banner is shown when nothing is pending`() {
        assertNull(build(assignment("A")).banner)
    }

    @Test
    fun `an empty list reads as calm rather than broken`() {
        val state = build()

        assertTrue(state.isEmpty)
        assertEquals("No assignments right now.", state.emptyMessage)
    }
}

class AssignmentDetailStateTest {

    private val now = 1_787_802_731_000L
    private val minute = 60_000L

    private fun assignment(
        description: String? = null,
        hops: Int = 0,
        latencyMinutes: Long = 0,
        status: ResponderStatus = ResponderStatus.ASSIGNED,
        synced: Boolean = true,
    ) = Assignment(
        emergencyCode = "BLG-2026-0041",
        typeCode = "FLOOD",
        filedAtMs = now - (latencyMinutes + 5) * minute,
        receivedAtMs = now - 5 * minute,
        status = status,
        priorityLevel = PriorityLevel.CRITICAL,
        description = description,
        affectedCount = 5,
        childrenCount = 2,
        elderlyCount = 1,
        mobilityLimitedCount = 1,
        isLifeThreatening = true,
        hopCount = hops,
        statusSynced = synced,
    )

    /**
     * The app never machine-translates an emergency. Nuance loss in a rescue
     * description can cost lives, and a responder reading a mistranslation has
     * no way to know it happened.
     */
    @Test
    fun `the resident's words are shown verbatim with a note that they are untranslated`() {
        val words = "Taas na an tubig, aada kami ha atop"
        val state = AssignmentDetailStateFactory.build(assignment(description = words), now)

        assertEquals(words, state.residentWords)
        assertTrue(state.residentWordsNote.contains("not translated"))
    }

    @Test
    fun `a report with no description shows nothing rather than a placeholder`() {
        assertNull(AssignmentDetailStateFactory.build(assignment(), now).residentWords)
        assertNull(AssignmentDetailStateFactory.build(assignment(description = "  "), now).residentWords)
    }

    /**
     * Children, elderly and mobility-limited are tinted because they change what
     * a responder brings — a carry, a second person, a different route. The
     * plain affected count does not.
     */
    @Test
    fun `only the counts that change what a responder brings are marked critical`() {
        val tiles = AssignmentDetailStateFactory.build(assignment(), now).tiles

        assertEquals(4, tiles.size)
        assertTrue(!tiles.first { it.label == "affected" }.isCritical)
        listOf("children", "elderly", "cannot walk alone").forEach { label ->
            assertTrue(
                tiles.first { it.label == label }.isCritical,
                "$label was not marked as changing what to bring",
            )
        }
    }

    @Test
    fun `absent vulnerability counts produce no tiles at all`() {
        val plain = assignment().copy(childrenCount = 0, elderlyCount = 0, mobilityLimitedCount = 0)
        val tiles = AssignmentDetailStateFactory.build(plain, now).tiles

        assertEquals(1, tiles.size)
        assertEquals("affected", tiles.single().label)
    }

    // --- mesh latency ------------------------------------------------------

    /**
     * A responder walking towards an address described eight minutes ago is
     * entitled to know that, and to plan for having been overtaken by events.
     */
    @Test
    fun `a mesh-delayed assignment warns that the situation may have changed`() {
        val note = AssignmentDetailStateFactory.meshLatencyNote(
            assignment(hops = 3, latencyMinutes = 5)
        )

        assertEquals(
            "Reached the command center after 3 mesh hops, 5 min behind filing. " +
                "The situation may have changed.",
            note,
        )
    }

    @Test
    fun `an assignment that never crossed the mesh carries no latency note`() {
        assertNull(AssignmentDetailStateFactory.meshLatencyNote(assignment(hops = 0)))
    }

    @Test
    fun `a fast mesh delivery still names the hops without an absurd zero minutes`() {
        val note = AssignmentDetailStateFactory.meshLatencyNote(
            assignment(hops = 1, latencyMinutes = 0)
        )!!

        assertTrue(note.contains("1 mesh hop"))
        assertTrue(!note.contains("0 min"), "the note said '0 min behind filing'")
    }

    /** Reasoning is shown so a responder can override the ranking with judgement. */
    @Test
    fun `the priority reasons travel to the responder`() {
        val plain = AssignmentDetailStateFactory.build(assignment(), now)

        // No PriorityResult attached here, so the list is empty rather than invented.
        assertTrue(plain.priorityReasons.isEmpty())
    }
}

class ActionBarStateTest {

    private fun bar(status: ResponderStatus, synced: Boolean = true) =
        ActionBarStateFactory.forStatus(status, synced)

    /** One decision per state: the next action is the only filled button. */
    @Test
    fun `every state offers exactly one primary action`() {
        ResponderStatus.entries.forEach {
            assertTrue(bar(it).primaryLabel.isNotBlank(), "$it has no primary action")
        }
    }

    @Test
    fun `an assigned job offers accept with a decline escape`() {
        val state = bar(ResponderStatus.ASSIGNED)

        assertEquals("ACCEPT", state.primaryLabel)
        assertEquals(ActionTone.CONFIRM, state.primaryTone)
        assertEquals("Decline — cannot reach", state.secondaryLabel)
        assertNull(state.statusPill)
    }

    /**
     * A responder who assumes the resident already knows may not follow up. The
     * pill is explicit that notification depends on signal.
     */
    @Test
    fun `accepting says plainly that telling the resident depends on signal`() {
        val pill = bar(ResponderStatus.ACCEPTED).statusPill!!

        assertTrue(pill.contains("when signal allows"))
    }

    @Test
    fun `the status ladder advances one step at a time`() {
        assertEquals("EN ROUTE", bar(ResponderStatus.ACCEPTED).primaryLabel)
        assertEquals("ON SITE", bar(ResponderStatus.EN_ROUTE).primaryLabel)
        assertEquals("RESOLVED", bar(ResponderStatus.ON_SITE).primaryLabel)
    }

    @Test
    fun `on site is the only state that offers escalation`() {
        assertEquals("Needs more help — escalate", bar(ResponderStatus.ON_SITE).escalateLabel)

        ResponderStatus.entries.filter { it != ResponderStatus.ON_SITE }.forEach {
            assertNull(bar(it).escalateLabel, "$it offered escalation")
        }
    }

    // --- the honesty rule, applied to the responder ------------------------

    /**
     * The responder's own status obeys the same rule as a resident's report:
     * resolved on this phone is not resolved at the command center.
     */
    @Test
    fun `a resolution that has not uploaded says so`() {
        val pill = bar(ResponderStatus.RESOLVED, synced = false).statusPill!!

        assertEquals("RESOLVED on this phone · status not yet uploaded", pill)
    }

    @Test
    fun `a resolution that has uploaded says that instead`() {
        assertEquals("RESOLVED · uploaded", bar(ResponderStatus.RESOLVED, synced = true).statusPill)
    }

    @Test
    fun `a declined assignment is equally honest about not having uploaded`() {
        assertTrue(
            bar(ResponderStatus.DECLINED, synced = false).statusPill!!.contains("not yet uploaded")
        )
    }

    /** Nothing left to do, so nothing invites a second tap. */
    @Test
    fun `a closed assignment offers no enabled action`() {
        listOf(ResponderStatus.RESOLVED, ResponderStatus.DECLINED).forEach {
            val state = bar(it)
            assertTrue(!state.primaryEnabled, "$it left an enabled button")
            assertEquals(ActionTone.CLOSED, state.primaryTone)
        }
    }

    /** Green means something confirmed, here as everywhere else in the app. */
    @Test
    fun `only accept and resolve carry the confirming tone`() {
        assertEquals(ActionTone.CONFIRM, bar(ResponderStatus.ASSIGNED).primaryTone)
        assertEquals(ActionTone.CONFIRM, bar(ResponderStatus.ON_SITE).primaryTone)
        assertEquals(ActionTone.ADVANCE, bar(ResponderStatus.ACCEPTED).primaryTone)
        assertEquals(ActionTone.ADVANCE, bar(ResponderStatus.EN_ROUTE).primaryTone)
    }

    @Test
    fun `declining asks for a reason from a fixed set`() {
        assertEquals(3, ActionBarStateFactory.declineReasons.size)
        assertTrue(ActionBarStateFactory.declineReasons.contains("Road impassable"))
        assertTrue(ActionBarStateFactory.DECLINE_EXPLANATION.contains("returns to the command center"))
    }
}
