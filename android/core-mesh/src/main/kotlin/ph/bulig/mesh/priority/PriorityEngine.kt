package ph.bulig.mesh.priority

import kotlin.math.floor
import kotlin.math.min
import ph.bulig.mesh.model.EmergencyPayload

enum class PriorityLevel { LOW, MODERATE, HIGH, CRITICAL }

/**
 * How much a single rule contributed, and why.
 *
 * The trace is the point. A resident and a responder both see the reasoning
 * rather than a coloured word, and neither has to trust a number they cannot
 * interrogate.
 */
data class PriorityFactor(
    val rule: String,
    val detail: String,
    val points: Int,
)

data class PriorityEscalation(
    val rule: String,
    val applied: Boolean,
    val note: String,
)

data class PriorityResult(
    val score: Int,
    val level: PriorityLevel,
    val factors: List<PriorityFactor>,
    val escalations: List<PriorityEscalation>,
    val configVersion: Int,
) {
    /**
     * Plain-language reasons, in the order the design shows them: the
     * life-threatening flag first, then vulnerability, then context.
     *
     * @see docs/design/HANDOFF.md — artboards 05 and 11
     */
    fun reasons(): List<String> = factors.mapNotNull { factor ->
        when (factor.rule) {
            "life_threatening" -> "You marked this life-threatening"
            "affected_count" -> factor.detail.takeIf { factor.points > 0 }
                ?.let { "${it.substringBefore(" person")} people affected" }
            "children" -> peopleReason(factor.detail, "child affected", "children affected")
            "elderly" -> peopleReason(
                factor.detail, "elderly person affected", "elderly people affected",
            )
            "mobility_limited" -> peopleReason(
                factor.detail, "person cannot walk without help",
                "people cannot walk without help",
            )
            "multi_hop_arrival" -> "Reached the command center over ${factor.detail}"
            "report_age" -> "Still unresolved after ${factor.detail.substringBefore(" ")}"
            // Base severity is the emergency type itself, which the screen
            // already shows in its header. Repeating it reads as padding.
            else -> null
        }
    }

    /**
     * [singular] and [plural] carry the whole phrase, including any trailing
     * words, so a reason like "cannot walk without help" is not left reading
     * "1 person cannot walk without help affected".
     */
    private fun peopleReason(detail: String, singular: String, plural: String): String {
        val count = detail.substringBefore(" ").toIntOrNull() ?: 0
        return if (count == 1) "1 $singular" else "$count $plural"
    }
}

/**
 * Scoring configuration.
 *
 * MIRRORS `App\Services\Priority\PriorityConfig::default()` in the Laravel
 * backend. The two implementations are pinned together by a shared fixture
 * asserted in both test suites — see `PriorityParityTest`.
 *
 * On-device scoring exists so a resident sees a priority immediately, offline,
 * with no spinner and no server. The server recomputes on ingest and its answer
 * is authoritative; a divergence means a stale app build, which is itself worth
 * knowing.
 */
data class PriorityConfig(
    val version: Int = 1,
    val lifeThreatening: Int = 25,
    val affectedBands: List<Pair<Int, Int>> = listOf(
        1 to 0, 5 to 5, 10 to 10, 25 to 15, Int.MAX_VALUE to 20,
    ),
    val perChild: Int = 4,
    val childCap: Int = 12,
    val perElderly: Int = 3,
    val elderlyCap: Int = 9,
    val perMobilityLimited: Int = 5,
    val mobilityCap: Int = 15,
    val perHourAge: Int = 2,
    val ageCap: Int = 10,
    val multiHopArrival: Int = 3,
    val multiHopMinHops: Int = 2,
    val scoreMax: Int = 100,
    val bands: List<Pair<Int, PriorityLevel>> = listOf(
        24 to PriorityLevel.LOW,
        44 to PriorityLevel.MODERATE,
        69 to PriorityLevel.HIGH,
        Int.MAX_VALUE to PriorityLevel.CRITICAL,
    ),
    val lifeThreateningMinHigh: Boolean = true,
    val mobilityLifeThreatCritical: Boolean = true,
    val staleNewRaisesBandHours: Int = 6,
)

/**
 * What the emergency type contributes, and whether the type itself implies a
 * threat to life. Supplied by the caller because types are configurable data on
 * the server, not constants in the app.
 */
data class TypeSeverity(
    val code: String,
    val baseSeverity: Int,
    val isLifeThreatening: Boolean,
)

/**
 * Deterministic, explainable prioritisation, computed on the phone.
 *
 * Deliberately not machine learning: a barangay operator must be able to see why
 * one incident outranks another, and a panel must be able to audit it.
 *
 * @see docs/08-priority-engine.md
 */
class PriorityEngine(private val config: PriorityConfig = PriorityConfig()) {

    fun score(
        type: TypeSeverity,
        payload: EmergencyPayload,
        reportedAtMs: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
        firstHopCount: Int = 0,
        isTerminal: Boolean = false,
        isUntriaged: Boolean = true,
    ): PriorityResult {
        val factors = mutableListOf<PriorityFactor>()
        var score = type.baseSeverity
        factors += PriorityFactor("base_severity", type.code, type.baseSeverity)

        if (payload.isLifeThreatening) {
            score += config.lifeThreatening
            factors += PriorityFactor(
                "life_threatening", "reporter asserted", config.lifeThreatening,
            )
        }

        val affectedPoints = config.affectedBands.first { payload.affectedCount <= it.first }.second
        score += affectedPoints
        factors += PriorityFactor(
            "affected_count", persons(payload.affectedCount), affectedPoints,
        )

        score += capped(payload.childrenCount, config.perChild, config.childCap, "children", factors)
        score += capped(payload.elderlyCount, config.perElderly, config.elderlyCap, "elderly", factors)
        score += capped(
            payload.mobilityLimitedCount, config.perMobilityLimited, config.mobilityCap,
            "mobility_limited", factors,
        )

        // A report becomes more urgent simply by going unanswered.
        var ageHours = 0.0
        if (reportedAtMs != null && !isTerminal) {
            ageHours = ((nowMs - reportedAtMs).coerceAtLeast(0)) / 3_600_000.0
            val agePoints = min(
                config.ageCap.toDouble(), floor(ageHours) * config.perHourAge,
            ).toInt()
            if (agePoints > 0) {
                score += agePoints
                factors += PriorityFactor(
                    "report_age", "%.1f h unresolved".format(ageHours), agePoints,
                )
            }
        }

        // Several hops implies a connectivity dead zone, which makes reaching
        // the people involved harder.
        if (firstHopCount >= config.multiHopMinHops) {
            score += config.multiHopArrival
            factors += PriorityFactor(
                "multi_hop_arrival", "arrived at hop $firstHopCount", config.multiHopArrival,
            )
        }

        score = score.coerceIn(0, config.scoreMax)
        var level = config.bands.first { score <= it.first }.second

        val escalations = mutableListOf<PriorityEscalation>()

        // Escalations run after banding so they can express floors a raw score
        // would miss — a life-threatening report must never read as MODERATE.
        if (config.lifeThreateningMinHigh && (payload.isLifeThreatening || type.isLifeThreatening)) {
            val applied = level < PriorityLevel.HIGH
            if (applied) level = PriorityLevel.HIGH
            escalations += PriorityEscalation(
                "life_threatening_min_high", applied,
                "life-threatening reports are at least HIGH",
            )
        }

        if (config.mobilityLifeThreatCritical &&
            payload.isLifeThreatening && payload.mobilityLimitedCount > 0
        ) {
            val applied = level < PriorityLevel.CRITICAL
            if (applied) level = PriorityLevel.CRITICAL
            escalations += PriorityEscalation(
                "mobility_life_threat_critical", applied,
                "life threat with mobility-limited persons present",
            )
        }

        // A report nobody has triaged is itself a problem worth surfacing.
        if (isUntriaged && ageHours > config.staleNewRaisesBandHours) {
            val applied = level < PriorityLevel.CRITICAL
            if (applied) level = PriorityLevel.entries[level.ordinal + 1]
            escalations += PriorityEscalation(
                "stale_new_raises_band", applied,
                "untriaged for %.1f h".format(ageHours),
            )
        }

        return PriorityResult(score, level, factors, escalations, config.version)
    }

    private fun capped(
        count: Int,
        per: Int,
        cap: Int,
        rule: String,
        factors: MutableList<PriorityFactor>,
    ): Int {
        if (count <= 0) return 0

        val points = min(cap, count * per)
        factors += PriorityFactor(rule, persons(count), points)
        return points
    }

    private fun persons(count: Int) = "$count ${if (count == 1) "person" else "persons"}"
}
