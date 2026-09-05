package ph.bulig.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.bulig.app.components.ConnectivityBanner
import ph.bulig.app.components.DeliveryChip
import ph.bulig.app.theme.BuligColors
import ph.bulig.app.theme.BuligDimens
import ph.bulig.app.theme.BuligTheme
import ph.bulig.app.theme.BuligType
import ph.bulig.data.presentation.HomeStateFactory
import ph.bulig.data.presentation.HomeUiState
import ph.bulig.data.presentation.ReportRowState

/**
 * Artboard 01 — Home.
 *
 * Connectivity first, one enormous action second, everything else subordinate.
 * A frightened person gets from launch to "I have reported this" in the fewest
 * taps possible: the emergency button goes straight to the type picker with no
 * confirmation dialog and no hold gesture. Gloves, rain and wet screens defeat
 * gestures, so every action here is a plain tap.
 *
 * All state is derived by [HomeStateFactory] in `:data`, which is unit-tested.
 * This file renders; it decides nothing.
 *
 * @see docs/design/HANDOFF.md — artboard 01
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onReportEmergency: () -> Unit,
    onOpenMesh: () -> Unit,
    onOpenReport: (ReportRowState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BuligColors.Canvas),
    ) {
        // Persistent, never collapsed, never behind a menu.
        ConnectivityBanner(state = state.banner.state, count = state.pendingCount)

        if (state.showMeshStrip) {
            MeshStrip(text = state.meshStripText, onClick = onOpenMesh)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(BuligDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(BuligDimens.GapLarge),
        ) {
            Spacer(Modifier.weight(1f))

            EmergencyButton(onClick = onReportEmergency)

            if (state.recentReports.isNotEmpty()) {
                SectionLabel(text = "MY REPORTS", trailing = state.totalReportCount.toString())
                RecentReportsCard(rows = state.recentReports, onOpenReport = onOpenReport)
            }

            Spacer(Modifier.weight(1f))

            PrototypeDisclaimer()
        }
    }
}

/**
 * The one action this screen exists for. 212dp tall, reachable one-handed with a
 * thumb, and the only element on the screen with a shadow.
 */
@Composable
private fun EmergencyButton(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(BuligDimens.EmergencyButtonHeight)
            .background(BuligColors.StateDanger, RoundedCornerShape(BuligDimens.PanelRadius))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Sos,
            contentDescription = null,
            tint = BuligColors.InkInverse,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "REPORT\nEMERGENCY",
            color = BuligColors.InkInverse,
            fontSize = BuligType.EmergencyLabel,
            lineHeight = BuligType.EmergencyLabelLineHeight,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            letterSpacing = (-0.3).sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Isumat an Emerhensya",
            color = BuligColors.InkInverse.copy(alpha = 0.88f),
            fontSize = BuligType.Body,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(10.dp))

        // The reassurance that matters most on this screen.
        Row(
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.18f), CircleShape)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.WifiOff,
                contentDescription = null,
                tint = BuligColors.InkInverse,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = "Works without signal",
                color = BuligColors.InkInverse,
                fontSize = BuligType.Eyebrow,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Makes the mesh visible without demanding anything of the resident. */
@Composable
private fun MeshStrip(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BuligColors.Surface)
            .clickable(onClick = onClick)
            .heightIn(min = BuligDimens.MeshStripHeight)
            .padding(horizontal = BuligDimens.ScreenPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Hub,
            contentDescription = null,
            tint = BuligColors.Brand,
            modifier = Modifier.size(19.dp),
        )
        Text(
            text = text,
            color = BuligColors.Ink,
            fontSize = BuligType.BodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "Mesh",
            color = BuligColors.BrandStrong,
            fontSize = BuligType.ScreenSubtitle,
            fontWeight = FontWeight.SemiBold,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = BuligColors.BrandStrong,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String, trailing: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            color = BuligColors.InkMuted,
            fontSize = BuligType.Eyebrow,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(BuligColors.Border),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                color = BuligColors.InkSubtle,
                fontSize = BuligType.Eyebrow,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun RecentReportsCard(
    rows: List<ReportRowState>,
    onOpenReport: (ReportRowState) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BuligColors.Surface, RoundedCornerShape(BuligDimens.PanelRadius))
            .border(
                1.dp, BuligColors.Border, RoundedCornerShape(BuligDimens.PanelRadius),
            ),
    ) {
        rows.forEachIndexed { index, row ->
            ReportRow(row = row, onClick = { onOpenReport(row) })
            if (index < rows.lastIndex) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(BuligColors.Border),
                )
            }
        }
    }
}

@Composable
private fun ReportRow(row: ReportRowState, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(BuligDimens.CardPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BuligDimens.Gap),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = row.typeLabelWar?.let { "${row.typeLabelEn} / $it" } ?: row.typeLabelEn,
                color = BuligColors.Ink,
                fontSize = BuligType.ListRowTitle,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The chip is never the only explanation — the plain-language
            // sentence sits beneath it on every surface that shows one.
            Text(
                text = row.presentation.sentence,
                color = BuligColors.InkMuted,
                fontSize = BuligType.ScreenSubtitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val code = row.emergencyCode
            if (code != null) {
                Text(
                    text = code,
                    color = BuligColors.InkSubtle,
                    fontSize = BuligType.MonoMeta,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        DeliveryChip(
            state = row.presentation.state,
            hopCount = row.handoffCount,
        )
    }
}

/** Required on this artboard, and it must survive into the build. */
@Composable
private fun PrototypeDisclaimer() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BuligColors.Surface, RoundedCornerShape(BuligDimens.PanelRadius))
            .border(1.dp, BuligColors.Border, RoundedCornerShape(BuligDimens.PanelRadius))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = BuligColors.StateDanger,
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = "Capstone prototype — not a replacement for official emergency " +
                "services. Call 911 when you have signal.",
            color = BuligColors.InkMuted,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun HomeScreenPreview() {
    BuligTheme {
        HomeScreen(
            state = HomeStateFactory.build(
                myReports = emptyList(),
                carriedForOthers = emptyList(),
                isOnline = false,
                isSyncing = false,
                nearbyPeerCount = 4,
            ),
            onReportEmergency = {},
            onOpenMesh = {},
            onOpenReport = {},
        )
    }
}
