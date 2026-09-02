package ph.bulig.data.presentation

import ph.bulig.mesh.priority.PriorityLevel
import ph.bulig.mesh.priority.PriorityResult

/** Where a responder has got to on one assignment. */
enum class ResponderStatus {
    ASSIGNED,
    ACCEPTED,
    EN_ROUTE,
    ON_SITE,
    RESOLVED,
    DECLINED,
}

/**
 * One assignment as a responder holds it.
 *
 * [filedAtMs] and [receivedAtMs] are deliberately separate fields. A report that
 * crossed three phones may reach a responder ten minutes after the resident
 * filed it, and every age shown on screen must be measured from filing. See
 * [AssignmentRow.ageMs].
 */
data class Assignment(
    /**
     * The server's id for this assignment, needed to PATCH a status change.
     *
     * Null for an assignment the server has never seen — which today means one
     * constructed in a test, but which leaves room for an assignment that
     * arrived over the mesh before the responder had signal.
     */
    val assignmentId: Long? = null,
    val emergencyCode: String,
    val typeCode: String,
    val filedAtMs: Long,
    val receivedAtMs: Long,
    val status: ResponderStatus = ResponderStatus.ASSIGNED,
    val priority: PriorityResult? = null,
    val priorityLevel: PriorityLevel = priority?.level ?: PriorityLevel.MODERATE,
    val description: String? = null,
    val affectedCount: Int = 1,
    val childrenCount: Int = 0,
    val elderlyCount: Int = 0,
    val mobilityLimitedCount: Int = 0,
    val isLifeThreatening: Boolean = false,
    val distanceM: Int? = null,
    val accuracyM: Int? = null,
    val purok: String? = null,
    val hopCount: Int = 0,
    /** False while the responder's own status change is still only on this phone. */
    val statusSynced: Boolean = true,
) {
    /** Arrived through the mesh rather than straight from the server. */
    val arrivedByMesh: Boolean get() = hopCount > 0

    /** How far behind filing this assignment reached anybody who could act on it. */
    val meshLatencyMs: Long get() = (receivedAtMs - filedAtMs).coerceAtLeast(0)
}

/** One card in the responder's list. */
data class AssignmentRow(
    val assignment: Assignment,
    val typeLabelEn: String,
    val ageMs: Long,
    val ageLabel: String,
    val affectedSummary: String,
    val distanceLabel: String?,
    val hopLabel: String?,
    val isExpanded: Boolean,
) {
    val emergencyCode: String get() = assignment.emergencyCode
    val priorityLevel: PriorityLevel get() = assignment.priorityLevel
    val statusChip: String? get() = when (assignment.status) {
        ResponderStatus.ASSIGNED -> null
        ResponderStatus.ACCEPTED -> "ACCEPTED"
        ResponderStatus.EN_ROUTE -> "EN ROUTE"
        ResponderStatus.ON_SITE -> "ON SITE"
        ResponderStatus.RESOLVED -> "RESOLVED"
        ResponderStatus.DECLINED -> "DECLINED"
    }
}

/** Artboard 10 — what a responder answers while walking. */
data class AssignmentListState(
    val responderName: String,
    val zone: String?,
    val rows: List<AssignmentRow>,
    val banner: String?,
    val footnote: String,
) {
    val isEmpty: Boolean get() = rows.isEmpty()
    val emptyMessage: String get() = "No assignments right now."
}

object AssignmentListStateFactory {

    /**
     * Stated on the screen because it changes how a responder reads every age on
     * it. Without this line a CRITICAL that took ten minutes to arrive looks
     * like it just happened.
     */
    const val FOOTNOTE =
        "Reports may arrive out of order over the mesh. Age is measured from when " +
            "the resident filed it, not when you received it."

    fun build(
        responderName: String,
        zone: String?,
        assignments: List<Assignment>,
        nowMs: Long,
        pendingUploads: Int = 0,
        typeLabels: Map<String, TypeLabel> = emptyMap(),
    ): AssignmentListState {
        val ordered = sort(assignments)

        return AssignmentListState(
            responderName = responderName,
            zone = zone,
            rows = ordered.mapIndexed { index, assignment ->
                row(assignment, nowMs, typeLabels, isExpanded = index == 0)
            },
            banner = pendingUploads.takeIf { it > 0 }?.let {
                "Uploading $it pending ${if (it == 1) "report" else "reports"}…"
            },
            footnote = FOOTNOTE,
        )
    }

    /**
     * Strict priority order, then oldest first. Never user-reorderable.
     *
     * A responder under pressure should not be able to bury a CRITICAL by
     * dragging it, and the ordering a barangay has to defend afterwards must be
     * the one the rules produced.
     */
    internal fun sort(assignments: List<Assignment>): List<Assignment> =
        assignments.sortedWith(
            compareBy<Assignment> { rank(it.priorityLevel) }
                .thenBy { it.filedAtMs }
        )

    private fun rank(level: PriorityLevel): Int = when (level) {
        PriorityLevel.CRITICAL -> 0
        PriorityLevel.HIGH -> 1
        PriorityLevel.MODERATE -> 2
        PriorityLevel.LOW -> 3
    }

    private fun row(
        assignment: Assignment,
        nowMs: Long,
        typeLabels: Map<String, TypeLabel>,
        isExpanded: Boolean,
    ): AssignmentRow {
        // From filing, never from receipt. This single expression is the whole
        // rule the footnote explains.
        val age = (nowMs - assignment.filedAtMs).coerceAtLeast(0)

        return AssignmentRow(
            assignment = assignment,
            typeLabelEn = typeLabels[assignment.typeCode]?.labelEn ?: assignment.typeCode,
            ageMs = age,
            ageLabel = ageLabel(age),
            affectedSummary = affectedSummary(assignment),
            distanceLabel = assignment.distanceM?.let { metres ->
                listOfNotNull("$metres m", assignment.purok).joinToString(" · ")
            },
            hopLabel = assignment.hopCount.takeIf { it > 0 }?.let {
                "via $it ${if (it == 1) "hop" else "hops"}"
            },
            isExpanded = isExpanded,
        )
    }

    internal fun ageLabel(ageMs: Long): String {
        val minutes = ageMs / 60_000
        val hours = minutes / 60

        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            hours < 24 -> "$hours ${if (hours == 1L) "hour" else "hours"} ago"
            else -> "${hours / 24} ${if (hours / 24 == 1L) "day" else "days"} ago"
        }
    }

    internal fun affectedSummary(a: Assignment): String = buildString {
        append("${a.affectedCount} affected")
        if (a.childrenCount > 0) append(" · ${a.childrenCount} children")
        if (a.elderlyCount > 0) append(" · ${a.elderlyCount} elderly")
        if (a.mobilityLimitedCount > 0) append(" · ${a.mobilityLimitedCount} cannot walk alone")
        if (a.isLifeThreatening) append(" · life-threatening")
    }
}

/**
 * One tile in the vulnerability grid.
 *
 * [isCritical] tints children, elderly and mobility-limited counts, and only
 * those, because they change what a responder *brings* — a carry, a second
 * person, a different route. The plain affected count does not.
 */
data class VulnerabilityTile(
    val value: Int,
    val label: String,
    val isCritical: Boolean,
)

/** Artboard 11 — everything a responder needs before deciding to accept. */
data class AssignmentDetailState(
    val assignment: Assignment,
    val typeLabelEn: String,
    /** The resident's own words, unaltered. Null when they wrote none. */
    val residentWords: String?,
    val residentWordsNote: String,
    val tiles: List<VulnerabilityTile>,
    val priorityReasons: List<String>,
    val meshLatencyNote: String?,
    val actionBar: ActionBarState,
)

object AssignmentDetailStateFactory {

    /**
     * The app never machine-translates an emergency.
     *
     * Nuance loss in a rescue description can cost lives, and a responder
     * reading a mistranslation has no way to know it happened. Showing the
     * original with a note is the honest option even when it is less convenient.
     */
    const val VERBATIM_NOTE = "Waray-Waray · not translated by the app"

    fun build(
        assignment: Assignment,
        nowMs: Long,
        typeLabels: Map<String, TypeLabel> = emptyMap(),
    ): AssignmentDetailState = AssignmentDetailState(
        assignment = assignment,
        typeLabelEn = typeLabels[assignment.typeCode]?.labelEn ?: assignment.typeCode,
        residentWords = assignment.description?.takeIf { it.isNotBlank() },
        residentWordsNote = VERBATIM_NOTE,
        tiles = tiles(assignment),
        // Shown so a responder can override the ranking with judgement, which
        // they could not do against a number with no reasoning attached.
        priorityReasons = assignment.priority?.reasons() ?: emptyList(),
        meshLatencyNote = meshLatencyNote(assignment),
        actionBar = ActionBarStateFactory.forStatus(assignment.status, assignment.statusSynced),
    )

    private fun tiles(a: Assignment): List<VulnerabilityTile> = buildList {
        add(VulnerabilityTile(a.affectedCount, "affected", isCritical = false))
        if (a.childrenCount > 0) add(VulnerabilityTile(a.childrenCount, "children", true))
        if (a.elderlyCount > 0) add(VulnerabilityTile(a.elderlyCount, "elderly", true))
        if (a.mobilityLimitedCount > 0) {
            add(VulnerabilityTile(a.mobilityLimitedCount, "cannot walk alone", true))
        }
    }

    /**
     * Why the situation on the ground may not match the report.
     *
     * A responder walking towards an address described eight minutes ago is
     * entitled to know that, and to plan for having been overtaken by events.
     */
    internal fun meshLatencyNote(a: Assignment): String? {
        if (!a.arrivedByMesh) return null

        val minutes = a.meshLatencyMs / 60_000
        val hops = "${a.hopCount} mesh ${if (a.hopCount == 1) "hop" else "hops"}"

        return if (minutes < 1) {
            "Reached the command center after $hops. The situation may have changed."
        } else {
            "Reached the command center after $hops, $minutes min behind filing. " +
                "The situation may have changed."
        }
    }
}

/**
 * Artboard 12 — the footer, as one decision.
 *
 * The next action is always the only filled button on screen. A responder
 * deciding between two equally-weighted buttons in the rain is a design failure,
 * so [secondary] is never styled as a competing choice.
 */
data class ActionBarState(
    val statusPill: String?,
    val primaryLabel: String,
    val primaryIcon: String,
    val primaryTone: ActionTone,
    val primaryEnabled: Boolean,
    val secondaryLabel: String? = null,
    val escalateLabel: String? = null,
)

/** Which of the three meanings a primary action carries. */
enum class ActionTone {
    /** Confirms something real has happened. Green. */
    CONFIRM,

    /** Moves the responder forward. Brand blue. */
    ADVANCE,

    /** Nothing left to do here. Never filled. */
    CLOSED,
}

object ActionBarStateFactory {

    fun forStatus(status: ResponderStatus, synced: Boolean): ActionBarState = when (status) {
        ResponderStatus.ASSIGNED -> ActionBarState(
            statusPill = null,
            primaryLabel = "ACCEPT",
            primaryIcon = "check_circle",
            primaryTone = ActionTone.CONFIRM,
            primaryEnabled = true,
            secondaryLabel = "Decline — cannot reach",
        )

        // The pill is explicit that telling the resident depends on signal. A
        // responder who assumes the resident already knows may not follow up.
        ResponderStatus.ACCEPTED -> ActionBarState(
            statusPill = "ACCEPTED · resident notified when signal allows",
            primaryLabel = "EN ROUTE",
            primaryIcon = "directions_walk",
            primaryTone = ActionTone.ADVANCE,
            primaryEnabled = true,
        )

        ResponderStatus.EN_ROUTE -> ActionBarState(
            statusPill = "EN ROUTE",
            primaryLabel = "ON SITE",
            primaryIcon = "location_on",
            primaryTone = ActionTone.ADVANCE,
            primaryEnabled = true,
        )

        ResponderStatus.ON_SITE -> ActionBarState(
            statusPill = "ON SITE",
            primaryLabel = "RESOLVED",
            primaryIcon = "task_alt",
            primaryTone = ActionTone.CONFIRM,
            primaryEnabled = true,
            escalateLabel = "Needs more help — escalate",
        )

        /**
         * The responder's own status obeys the same honesty rule as a resident's
         * report: locally resolved is not synced. The pill says so and the
         * button is deliberately not filled, because there is nothing further to
         * do and pretending otherwise would invite a second tap.
         */
        ResponderStatus.RESOLVED -> ActionBarState(
            statusPill = if (synced) {
                "RESOLVED · uploaded"
            } else {
                "RESOLVED on this phone · status not yet uploaded"
            },
            primaryLabel = "CLOSED",
            primaryIcon = "task_alt",
            primaryTone = ActionTone.CLOSED,
            primaryEnabled = false,
        )

        ResponderStatus.DECLINED -> ActionBarState(
            statusPill = if (synced) {
                "DECLINED · returned to the queue"
            } else {
                "DECLINED on this phone · not yet uploaded"
            },
            primaryLabel = "CLOSED",
            primaryIcon = "task_alt",
            primaryTone = ActionTone.CLOSED,
            primaryEnabled = false,
        )
    }

    /** The decline sheet. Reasons are fixed choices so they are analysable later. */
    val declineReasons: List<String> = listOf(
        "Road impassable",
        "Already on another call",
        "Too dangerous alone",
    )

    const val DECLINE_EXPLANATION =
        "It returns to the command center queue immediately, and to other " +
            "responders when signal allows. Tell them why:"
}
