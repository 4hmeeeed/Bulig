package ph.bulig.data.presentation

import ph.bulig.data.model.LocalReport
import ph.bulig.mesh.delivery.BannerFormatter
import ph.bulig.mesh.delivery.BannerPresentation
import ph.bulig.mesh.delivery.DeliveryState

/**
 * Artboard 07 — every report's delivery state at a glance.
 *
 * @see docs/design/HANDOFF.md — artboard 07
 */
data class MyReportsState(
    val banner: BannerPresentation,
    val rows: List<ReportRowState>,
    val totalCount: Int,
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    /** Reassuring, not an error — a resident with no emergencies is the good case. */
    val emptyMessage: String get() = "You have not filed any reports."
}

object MyReportsStateFactory {

    fun build(
        reports: List<LocalReport>,
        isOnline: Boolean,
        isSyncing: Boolean,
        typeLabels: Map<String, TypeLabel> = emptyMap(),
    ): MyReportsState {
        val pending = reports.count { it.isPendingSync }

        return MyReportsState(
            banner = BannerFormatter.present(
                state = HomeStateFactory.connectivityState(isOnline, isSyncing, pending),
                count = pending,
            ),
            rows = sort(reports).map { it.toRow(typeLabels) },
            totalCount = reports.size,
        )
    }

    /**
     * Newest first — but **undelivered never sinks below delivered**.
     *
     * Age alone would bury a report still stuck on the phone beneath one the
     * command center resolved yesterday. The unfinished ones are the only rows a
     * resident can still act on, by moving somewhere with more phones or more
     * signal, so they stay at the top regardless of when they were filed.
     */
    internal fun sort(reports: List<LocalReport>): List<LocalReport> =
        reports.sortedWith(
            compareBy<LocalReport> { deliveryRank(it.deliveryState) }
                .thenByDescending { it.packet.createdAtDeviceMs }
        )

    /**
     * Lower sorts higher. Resolved sinks furthest because it needs nothing from
     * anybody.
     */
    private fun deliveryRank(state: DeliveryState): Int = when (state) {
        DeliveryState.SAVED_LOCAL -> 0
        DeliveryState.RELAYED -> 1
        DeliveryState.DELIVERED -> 2
        DeliveryState.ASSIGNED, DeliveryState.EN_ROUTE, DeliveryState.ON_SITE -> 3
        DeliveryState.RESOLVED -> 4
    }

    private fun LocalReport.toRow(labels: Map<String, TypeLabel>): ReportRowState {
        val code = packet.payload.typeCode
        val label = labels[code]

        return ReportRowState(
            packetId = packetId,
            typeCode = code,
            typeLabelEn = label?.labelEn ?: code,
            typeLabelWar = label?.labelWar,
            emergencyCode = emergencyCode,
            timestampMs = packet.createdAtDeviceMs,
            priorityLevel = priorityLevel,
            handoffCount = handoffCount,
            presentation = ph.bulig.mesh.delivery.DeliveryFormatter.present(
                state = deliveryState,
                hopCount = handoffCount,
            ),
        )
    }
}
