package ph.bulig.data.presentation

import ph.bulig.data.model.LocalReport
import ph.bulig.mesh.delivery.BannerFormatter
import ph.bulig.mesh.delivery.BannerPresentation
import ph.bulig.mesh.delivery.ConnectivityState
import ph.bulig.mesh.delivery.DeliveryFormatter
import ph.bulig.mesh.delivery.DeliveryPresentation
import ph.bulig.mesh.model.PacketId

/**
 * One row in the Home screen's recent-reports card, and in My reports.
 *
 * The delivery [presentation] is produced by the shared formatter rather than
 * assembled here, so a screen cannot accidentally pair a green tick with "not
 * yet delivered".
 */
data class ReportRowState(
    val packetId: PacketId,
    val typeCode: String,
    val typeLabelEn: String,
    val typeLabelWar: String?,
    val emergencyCode: String?,
    val timestampMs: Long,
    val priorityLevel: String?,
    val handoffCount: Int,
    val presentation: DeliveryPresentation,
)

/**
 * Everything artboard 01 renders.
 *
 * Derived here, in pure Kotlin, so the rules that decide what a resident is told
 * are tested without an emulator. The Compose layer is a renderer: if a decision
 * lives in a `@Composable`, it cannot be tested in this environment and it does
 * not belong there.
 *
 * @see docs/design/HANDOFF.md — artboard 01
 */
data class HomeUiState(
    val banner: BannerPresentation,
    val nearbyPeerCount: Int,
    val carryingForOthersCount: Int,
    val recentReports: List<ReportRowState>,
    val totalReportCount: Int,
    /** Reports still owed to the server. Drives the banner's count. */
    val pendingCount: Int,
) {
    /** The mesh strip is only meaningful when there is a mesh to describe. */
    val showMeshStrip: Boolean get() = nearbyPeerCount > 0 || carryingForOthersCount > 0

    val meshStripText: String
        get() = when {
            nearbyPeerCount == 1 -> "1 Bulig phone nearby"
            else -> "$nearbyPeerCount Bulig phones nearby"
        }
}

/** Names for an emergency type, supplied by the server and cached locally. */
data class TypeLabel(
    val code: String,
    val labelEn: String,
    val labelWar: String?,
)

object HomeStateFactory {

    /** How many rows the Home card shows before deferring to "My reports". */
    const val RECENT_LIMIT = 2

    fun build(
        myReports: List<LocalReport>,
        carriedForOthers: List<LocalReport>,
        isOnline: Boolean,
        isSyncing: Boolean,
        nearbyPeerCount: Int,
        typeLabels: Map<String, TypeLabel> = emptyMap(),
    ): HomeUiState {
        val pendingCount = myReports.count { it.isPendingSync }

        return HomeUiState(
            banner = BannerFormatter.present(
                state = connectivityState(isOnline, isSyncing, pendingCount),
                count = pendingCount,
            ),
            nearbyPeerCount = nearbyPeerCount,
            carryingForOthersCount = carriedForOthers.size,
            recentReports = myReports.take(RECENT_LIMIT).map { it.toRow(typeLabels) },
            totalReportCount = myReports.size,
            pendingCount = pendingCount,
        )
    }

    /**
     * The banner reflects reality, not intent.
     *
     * Ordering matters: an in-flight upload is reported as SYNCING even though
     * the device is also online, and a phone with nothing queued is not
     * described as PENDING. Note that ONLINE is not SYNCED — having a signal is
     * not the same as the command center having your report.
     */
    internal fun connectivityState(
        isOnline: Boolean,
        isSyncing: Boolean,
        pendingCount: Int,
    ): ConnectivityState = when {
        isSyncing -> ConnectivityState.SYNCING
        !isOnline && pendingCount > 0 -> ConnectivityState.PENDING
        !isOnline -> ConnectivityState.OFFLINE
        pendingCount > 0 -> ConnectivityState.PENDING
        else -> ConnectivityState.ONLINE
    }

    private fun LocalReport.toRow(labels: Map<String, TypeLabel>): ReportRowState {
        val code = packet.payload.typeCode
        val label = labels[code]

        return ReportRowState(
            packetId = packetId,
            typeCode = code,
            // Falls back to the raw code rather than a placeholder: a resident
            // seeing "FLOOD" is unhelpful, but seeing "Unknown" is worse.
            typeLabelEn = label?.labelEn ?: code,
            typeLabelWar = label?.labelWar,
            emergencyCode = emergencyCode,
            timestampMs = packet.createdAtDeviceMs,
            priorityLevel = priorityLevel,
            handoffCount = handoffCount,
            presentation = DeliveryFormatter.present(
                state = deliveryState,
                hopCount = handoffCount,
            ),
        )
    }
}
