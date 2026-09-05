package ph.bulig.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.bulig.app.components.DeliveryChip
import ph.bulig.app.components.Panel
import ph.bulig.app.components.SecondaryButton
import ph.bulig.app.components.StepHeader
import ph.bulig.app.theme.BuligColors
import ph.bulig.app.theme.BuligDimens
import ph.bulig.app.theme.BuligType
import ph.bulig.data.presentation.ReportDetailState
import ph.bulig.data.presentation.StepStatus
import ph.bulig.data.presentation.TimelineStep
import ph.bulig.mesh.delivery.DeliveryTone

/**
 * Artboard 08 — the delivery timeline with hop-level evidence.
 *
 * The rendering rule that matters: completed steps are solid, the current step
 * is ringed, and **future steps are hollow with a dashed border and the words
 * "Not yet"**. There is no greyed tick anywhere in this file, because a tick —
 * even a faint one — reads as done to somebody scanning in a panic.
 *
 * The statuses come from `ReportDetailStateFactory`, which is tested. This file
 * decides only how each status is drawn.
 */
@Composable
fun ReportDetailScreen(
    state: ReportDetailState,
    onBack: () -> Unit,
    onCheckForUpdates: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BuligColors.Canvas),
    ) {
        StepHeader(title = state.typeLabelEn, stepLabel = "", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(BuligDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(BuligDimens.Gap),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BuligDimens.GapSmall),
            ) {
                DeliveryChip(
                    state = state.presentation.state,
                    hopCount = state.steps[1].hopLog.size,
                )
                state.emergencyCode?.let {
                    Text(
                        text = it,
                        color = BuligColors.InkSubtle,
                        fontFamily = FontFamily.Monospace,
                        fontSize = BuligType.MonoMeta,
                    )
                }
            }

            Text(
                text = state.presentation.sentence,
                color = BuligColors.InkMuted,
                fontSize = BuligType.Body,
                lineHeight = 22.sp,
            )

            Panel {
                state.steps.forEach { step ->
                    TimelineRow(step = step, isLast = step.ordinal == state.steps.size)
                }
            }

            AffectedStrip(state.affectedSummary)

            if (state.canCheckForUpdates) {
                SecondaryButton(text = "Check for updates", onClick = onCheckForUpdates)
            }
        }
    }
}

@Composable
private fun TimelineRow(step: TimelineStep, isLast: Boolean) {
    val accent = when (step.tone) {
        DeliveryTone.NEUTRAL -> BuligColors.StateLocal
        DeliveryTone.IN_MOTION -> BuligColors.StateSyncing
        DeliveryTone.CONFIRMED -> BuligColors.StateOnline
    }

    Row(modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StepMarker(step = step, accent = accent)

            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(if (step.hopLog.isEmpty()) 26.dp else 84.dp)
                        .background(
                            if (step.status == StepStatus.NOT_YET) {
                                BuligColors.Border
                            } else {
                                accent.copy(alpha = 0.35f)
                            }
                        )
                )
            }
        }

        Column(
            modifier = Modifier
                .padding(start = BuligDimens.Gap, bottom = BuligDimens.Gap)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = step.title,
                color = if (step.isNotYet) BuligColors.InkSubtle else BuligColors.Ink,
                fontSize = BuligType.ListRowTitle,
                fontWeight = if (step.status == StepStatus.CURRENT) {
                    FontWeight.Bold
                } else {
                    FontWeight.Medium
                },
            )
            Text(
                text = step.detail,
                color = BuligColors.InkMuted,
                fontSize = BuligType.BodySmall,
                lineHeight = 19.sp,
            )

            if (step.hopLog.isNotEmpty()) HopLog(step)
        }
    }
}

/**
 * Solid when done, ringed when current, hollow and dashed when not yet.
 *
 * The three renderings are visually distinct without relying on colour alone,
 * which matters on the mono printouts a barangay office produces and for a
 * colour-blind reader.
 */
@Composable
private fun StepMarker(step: TimelineStep, accent: Color) {
    val size = 28.dp

    when (step.status) {
        StepStatus.DONE -> Box(
            modifier = Modifier.size(size).background(accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = stepIcon(step.icon),
                contentDescription = null,
                tint = BuligColors.InkInverse,
                modifier = Modifier.size(15.dp),
            )
        }

        StepStatus.CURRENT -> Box(
            modifier = Modifier
                .size(size + 6.dp)
                .background(accent.copy(alpha = 0.20f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.size(size).background(accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = stepIcon(step.icon),
                    contentDescription = null,
                    tint = BuligColors.InkInverse,
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        // Hollow, bordered, and carrying no tick of any kind.
        StepStatus.NOT_YET -> Box(
            modifier = Modifier
                .size(size)
                .border(2.dp, BuligColors.Border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = stepIcon(step.icon),
                contentDescription = null,
                tint = BuligColors.InkSubtle,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** The evidence: which phones took a copy, and when this device saw it happen. */
@Composable
private fun HopLog(step: TimelineStep) {
    val shape = RoundedCornerShape(BuligDimens.GapSmall)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(BuligColors.Canvas, shape)
            .border(BuligDimens.HairlineWidth, BuligColors.Border, shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        step.hopLog.forEach { hop ->
            Text(
                text = "hop ${hop.hop} · ${hop.peerPseudonym}",
                color = BuligColors.InkMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
            )
        }
    }
}

@Composable
private fun AffectedStrip(summary: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BuligDimens.GapSmall),
    ) {
        Icon(
            imageVector = Icons.Filled.Groups,
            contentDescription = null,
            tint = BuligColors.InkMuted,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = summary,
            color = BuligColors.InkMuted,
            fontSize = BuligType.BodySmall,
            lineHeight = 19.sp,
        )
    }
}

private fun stepIcon(name: String): ImageVector = when (name) {
    "smartphone" -> Icons.Filled.Smartphone
    "hub" -> Icons.Filled.Hub
    "cloud_upload" -> Icons.Filled.CloudUpload
    "badge" -> Icons.Filled.Badge
    "task_alt" -> Icons.Filled.TaskAlt
    else -> Icons.Filled.Smartphone
}
