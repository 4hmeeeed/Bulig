package ph.bulig.mesh.priority

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ph.bulig.mesh.model.EmergencyPayload

/**
 * Parity between the on-device engine and the server's.
 *
 * The four worked examples are the same ones asserted in
 * `backend/tests/Unit/PriorityEngineTest.php` and documented in
 * `docs/08-priority-engine.md`. If Kotlin and PHP ever disagree about how a
 * report is scored, a resident would see one priority on their phone and an
 * operator a different one on the dashboard — for the same emergency.
 *
 * Do not "fix" a failure here by editing an expected value. Fix whichever
 * implementation drifted, or change both deliberately and bump the config
 * version.
 */
class PriorityParityTest {

    private val engine = PriorityEngine()
    private val now = 1_787_802_731_000L
    private val hour = 3_600_000L

    private fun type(code: String, severity: Int, lifeThreatening: Boolean) =
        TypeSeverity(code, severity, lifeThreatening)

    // Severities mirror EmergencyTypeSeeder.
    private val medical = type("MEDICAL", 35, true)
    private val fire = type("FIRE", 40, true)
    private val flood = type("FLOOD", 30, false)
    private val infra = type("INFRA", 15, false)
    private val missing = type("MISSING", 25, false)

    /** Example A — elderly cardiac emergency, 4 affected, arrived at hop 3. */
    @Test
    fun `example A scores 81 critical`() {
        val result = engine.score(
            type = medical,
            payload = EmergencyPayload(
                typeCode = "MEDICAL",
                affectedCount = 4,
                elderlyCount = 2,
                mobilityLimitedCount = 1,
                isLifeThreatening = true,
            ),
            reportedAtMs = now - hour,
            nowMs = now,
            firstHopCount = 3,
        )

        assertEquals(81, result.score)
        assertEquals(PriorityLevel.CRITICAL, result.level)
    }

    /** Example B — minor flooding, 3 affected, no vulnerable persons, fresh. */
    @Test
    fun `example B scores 35 moderate`() {
        val result = engine.score(
            type = flood,
            payload = EmergencyPayload(typeCode = "FLOOD", affectedCount = 3),
            reportedAtMs = now,
            nowMs = now,
        )

        assertEquals(35, result.score)
        assertEquals(PriorityLevel.MODERATE, result.level)
    }

    /** Example C — reported road damage, 1 affected, fresh. */
    @Test
    fun `example C scores 15 low`() {
        val result = engine.score(
            type = infra,
            payload = EmergencyPayload(typeCode = "INFRA", affectedCount = 1),
            reportedAtMs = now,
            nowMs = now,
        )

        assertEquals(15, result.score)
        assertEquals(PriorityLevel.LOW, result.level)
    }

    /** Example D — house fire, 12 affected incl. 3 children and 2 elderly, 2 h old. */
    @Test
    fun `example D clamps at 100 critical`() {
        val result = engine.score(
            type = fire,
            payload = EmergencyPayload(
                typeCode = "FIRE",
                affectedCount = 12,
                childrenCount = 3,
                elderlyCount = 2,
                isLifeThreatening = true,
            ),
            reportedAtMs = now - 2 * hour,
            nowMs = now,
        )

        assertEquals(100, result.score)
        assertEquals(PriorityLevel.CRITICAL, result.level)
    }

    @Test
    fun `the trace sums to the score`() {
        val result = engine.score(
            type = medical,
            payload = EmergencyPayload(
                typeCode = "MEDICAL",
                affectedCount = 4,
                elderlyCount = 2,
                mobilityLimitedCount = 1,
                isLifeThreatening = true,
            ),
            reportedAtMs = now - hour,
            nowMs = now,
            firstHopCount = 3,
        )

        assertEquals(
            result.score, result.factors.sumOf { it.points },
            "if the reasons do not add up to the score, the explanation is a fiction",
        )
    }

    @Test
    fun `a life threatening report is never below high`() {
        val result = engine.score(
            type = missing,
            payload = EmergencyPayload(
                typeCode = "MISSING", affectedCount = 1, isLifeThreatening = true,
            ),
            reportedAtMs = now,
            nowMs = now,
        )

        assertEquals(50, result.score)
        assertEquals(PriorityLevel.HIGH, result.level)
    }

    @Test
    fun `life threat with a mobility limited person is critical`() {
        val result = engine.score(
            type = type("OTHER", 10, false),
            payload = EmergencyPayload(
                typeCode = "OTHER",
                affectedCount = 1,
                mobilityLimitedCount = 1,
                isLifeThreatening = true,
            ),
            reportedAtMs = now,
            nowMs = now,
        )

        assertEquals(PriorityLevel.CRITICAL, result.level)
    }

    /**
     * Age acts twice by design: it adds capped points, and past the threshold it
     * also raises the band. Matches the PHP suite exactly.
     */
    @Test
    fun `a stale untriaged report gains points and a band`() {
        val fresh = engine.score(
            type = infra,
            payload = EmergencyPayload(typeCode = "INFRA", affectedCount = 1),
            reportedAtMs = now, nowMs = now,
        )
        val stale = engine.score(
            type = infra,
            payload = EmergencyPayload(typeCode = "INFRA", affectedCount = 1),
            reportedAtMs = now - 8 * hour, nowMs = now,
        )

        assertEquals(15, fresh.score)
        assertEquals(PriorityLevel.LOW, fresh.level)

        assertEquals(25, stale.score)
        assertEquals(PriorityLevel.HIGH, stale.level)
        assertTrue(stale.escalations.single { it.rule == "stale_new_raises_band" }.applied)
    }

    @Test
    fun `a closed incident does not accrue age points`() {
        val result = engine.score(
            type = infra,
            payload = EmergencyPayload(typeCode = "INFRA", affectedCount = 1),
            reportedAtMs = now - 72 * hour,
            nowMs = now,
            isTerminal = true,
            isUntriaged = false,
        )

        assertEquals(15, result.score)
        assertTrue(result.factors.none { it.rule == "report_age" })
    }

    @Test
    fun `scoring is deterministic`() {
        val payload = EmergencyPayload(
            typeCode = "FIRE", affectedCount = 7, childrenCount = 1,
        )
        val a = engine.score(fire, payload, now - 90 * 60_000, now)
        val b = engine.score(fire, payload, now - 90 * 60_000, now)

        assertEquals(a.score, b.score)
        assertEquals(a.factors, b.factors)
    }

    // --- reason strings for artboards 05 and 11 ---------------------------

    @Test
    fun `reasons read as plain language a frightened person can parse`() {
        val reasons = engine.score(
            type = flood,
            payload = EmergencyPayload(
                typeCode = "FLOOD",
                affectedCount = 5,
                childrenCount = 2,
                elderlyCount = 1,
                mobilityLimitedCount = 1,
                isLifeThreatening = true,
            ),
            reportedAtMs = now, nowMs = now,
        ).reasons()

        assertEquals("You marked this life-threatening", reasons.first())
        assertTrue(reasons.any { it == "5 people affected" }, "got $reasons")
        assertTrue(reasons.any { it == "2 children affected" }, "got $reasons")
        assertTrue(reasons.any { it == "1 elderly person affected" }, "got $reasons")
        assertTrue(
            reasons.any { it == "1 person cannot walk without help" }, "got $reasons",
        )

        // The type is already the screen's headline; repeating it reads as padding.
        assertTrue(reasons.none { it.contains("FLOOD") })
    }

    @Test
    fun `a report with nothing notable produces no reasons to pad the screen`() {
        val reasons = engine.score(
            type = infra,
            payload = EmergencyPayload(typeCode = "INFRA", affectedCount = 1),
            reportedAtMs = now, nowMs = now,
        ).reasons()

        assertTrue(reasons.isEmpty(), "showed reasons that did not fire: $reasons")
    }

    @Test
    fun `singular and plural reasons are both correct`() {
        val one = engine.score(
            type = flood,
            payload = EmergencyPayload(
                typeCode = "FLOOD", affectedCount = 2, childrenCount = 1, elderlyCount = 1,
            ),
            reportedAtMs = now, nowMs = now,
        ).reasons()

        assertTrue(one.any { it == "1 child affected" }, "got $one")
        assertTrue(one.any { it == "1 elderly person affected" }, "got $one")
    }
}
