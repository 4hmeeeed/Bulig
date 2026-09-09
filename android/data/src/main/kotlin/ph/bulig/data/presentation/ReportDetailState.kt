package ph.bulig.data.presentation

import ph.bulig.data.model.LocalReport
import ph.bulig.mesh.delivery.DeliveryFormatter
import ph.bulig.mesh.delivery.DeliveryPresentation
import ph.bulig.mesh.delivery.DeliveryState
import ph.bulig.mesh.delivery.DeliveryTone

/**
 * How far a timeline step has got.
 *
 * [NOT_YET] is the one that matters. The design is explicit that future steps
 * are drawn hollow and labelled "Not yet" — never as greyed ticks — because a
 * tick, even a faint one, reads as *done* to somebody scanning a screen in a
 * panic. There is no third rendering that means "probably soon".
 */
enum class StepStatus { DONE, CURRENT, NOT_YET }

/** One row of the delivery timeline. */
data class TimelineStep(
    val ordinal: Int,
    val title: String,
    val detail: String,
    val icon: String,
    val status: StepStatus,
    val tone: DeliveryTone,
    /** Populated only on the relay step, and only from what this phone observed. */
    val hopLog: List<HopLine> = emptyList(),
) {
    val isNotYet: Boolean get() = status == StepStatus.NOT_YET
}

/**
 * One line of the hop log.
 *
 * [peerPseudonym] is a rotating random id, never a resident's name or number.
 * The whole point of the log is to show the resident that real phones carried
 * their report, without telling them whose.
 */
data class HopLine(
    val hop: Int,
    val peerPseudonym: String,
    val observedAtMs: Long,
)

/** Artboard 08 — the delivery timeline in full, with hop-level evidence. */
data class ReportDetailState(
    val typeLabelEn: String,
    val typeLabelWar: String?,
    val emergencyCode: String?,
    val createdAtMs: Long,
    val priorityLevel: String?,
    val presentation: DeliveryPresentation,
    val steps: List<TimelineStep>,
    val affectedSummary: String,
    val canCheckForUpdates: Boolean,
) {
    /** What the resident is owed at the top of the screen, before any detail. */
    val headline: String get() = presentation.label

    val isDelivered: Boolean get() = presentation.state.isConfirmedByCommandCenter
}

object ReportDetailStateFactory {

    private const val STEP_CREATED = 1
    private const val STEP_RELAYED = 2
    private const val STEP_DELIVERED = 3
    private const val STEP_ASSIGNED = 4
    private const val STEP_RESOLVED = 5

    fun build(
        report: LocalReport,
        typeLabels: Map<String, TypeLabel> = emptyMap(),
    ): ReportDetailState {
        val label = typeLabels[report.packet.payload.typeCode]
        val state = report.deliveryState

        return ReportDetailState(
            typeLabelEn = label?.labelEn ?: report.packet.payload.typeCode,
            typeLabelWar = label?.labelWar,
            emergencyCode = report.emergencyCode,
            createdAtMs = report.packet.createdAtDeviceMs,
            priorityLevel = report.priorityLevel,
            presentation = DeliveryFormatter.present(state, report.handoffCount),
            steps = steps(report),
            affectedSummary = affectedSummary(report),
            // Pointless while there is no connection to check with, and a button
            // that does nothing is worse than one that is honestly disabled.
            canCheckForUpdates = !state.isConfirmedByCommandCenter || state != DeliveryState.RESOLVED,
        )
    }

    /**
     * The five steps, with status derived from one place.
     *
     * Every step's status comes from comparing its ordinal against the report's
     * actual state — so a step cannot be drawn as reached unless the state
     * machine, which only advances on evidence, actually reached it.
     */
    private fun steps(report: LocalReport): List<TimelineStep> {
        val reached = reachedStep(report.deliveryState)
        val hops = hopLog(report)

        return listOf(
            step(
                STEP_CREATED, reached,
                title = "Created on your phone",
                doneDetail = "Saved on this phone",
                pendingDetail = "Not yet",
                icon = "smartphone",
                tone = DeliveryTone.NEUTRAL,
            ),
            step(
                STEP_RELAYED, reached,
                title = if (hops.isEmpty()) "Passed to nearby phones" else relayTitle(hops.size),
                doneDetail = "Each hop is a phone that took a copy onward",
                pendingDetail = "Not yet — no other phone has taken a copy",
                icon = "hub",
                tone = DeliveryTone.IN_MOTION,
                hopLog = hops,
            ),
            step(
                STEP_DELIVERED, reached,
                title = "Delivered to command center",
                doneDetail = "The barangay has your report",
                pendingDetail = "Not yet — waiting for a phone with signal",
                icon = "cloud_upload",
                tone = DeliveryTone.CONFIRMED,
            ),
            step(
                STEP_ASSIGNED, reached,
                title = "Responder assigned",
                doneDetail = "Someone has been sent",
                pendingDetail = "Not yet",
                icon = "badge",
                tone = DeliveryTone.CONFIRMED,
            ),
            step(
                STEP_RESOLVED, reached,
                title = "Resolved",
                doneDetail = "The barangay closed this report",
                pendingDetail = "Not yet",
                icon = "task_alt",
                tone = DeliveryTone.CONFIRMED,
            ),
        )
    }

    private fun step(
        ordinal: Int,
        reached: Int,
        title: String,
        doneDetail: String,
        pendingDetail: String,
        icon: String,
        tone: DeliveryTone,
        hopLog: List<HopLine> = emptyList(),
    ): TimelineStep {
        val status = when {
            ordinal < reached -> StepStatus.DONE
            ordinal == reached -> StepStatus.CURRENT
            else -> StepStatus.NOT_YET
        }

        return TimelineStep(
            ordinal = ordinal,
            title = title,
            detail = if (status == StepStatus.NOT_YET) pendingDetail else doneDetail,
            icon = icon,
            status = status,
            // A step not yet reached is never tinted with its eventual colour —
            // a green glow under "Not yet" would undo the words above it.
            tone = if (status == StepStatus.NOT_YET) DeliveryTone.NEUTRAL else tone,
            hopLog = hopLog,
        )
    }

    /** Which step the report is currently sitting on. */
    private fun reachedStep(state: DeliveryState): Int = when (state) {
        DeliveryState.SAVED_LOCAL -> STEP_CREATED
        DeliveryState.RELAYED -> STEP_RELAYED
        DeliveryState.DELIVERED -> STEP_DELIVERED
        DeliveryState.ASSIGNED, DeliveryState.EN_ROUTE, DeliveryState.ON_SITE -> STEP_ASSIGNED
        DeliveryState.RESOLVED -> STEP_RESOLVED
    }

    private fun relayTitle(count: Int): String =
        if (count == 1) "Relayed via 1 phone" else "Relayed via $count phones"

    /** Numbered in the order this phone observed them, which is the only order it knows. */
    private fun hopLog(report: LocalReport): List<HopLine> =
        report.handoffs
            .sortedBy { it.atMs }
            .mapIndexed { index, handoff ->
                HopLine(
                    hop = index + 1,
                    peerPseudonym = handoff.peerId.value,
                    observedAtMs = handoff.atMs,
                )
            }

    /**
     * The affected strip, in plain words rather than a field dump.
     *
     * Life-threatening is stated last and unabbreviated, because it is the one
     * item on this line that changes what anybody does about the report.
     */
    internal fun affectedSummary(report: LocalReport): String {
        val p = report.packet.payload

        val parts = buildList {
            add(plural(p.affectedCount, "person affected", "people affected"))
            if (p.childrenCount > 0) add("${p.childrenCount} children")
            if (p.elderlyCount > 0) add("${p.elderlyCount} elderly")
            if (p.mobilityLimitedCount > 0) {
                add(plural(p.mobilityLimitedCount, "cannot walk alone", "cannot walk alone"))
            }
        }

        return buildString {
            append(parts.joinToString(" · "))
            if (p.isLifeThreatening) append(" · marked life-threatening")
        }
    }

    private fun plural(count: Int, singular: String, plural: String): String =
        "$count ${if (count == 1) singular else plural}"
}
