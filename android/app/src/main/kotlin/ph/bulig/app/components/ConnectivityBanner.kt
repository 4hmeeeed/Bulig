package ph.bulig.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.SignalCellularOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.bulig.app.theme.BuligColors
import ph.bulig.app.theme.BuligDimens
import ph.bulig.app.theme.BuligTheme
import ph.bulig.app.theme.BuligType
import ph.bulig.mesh.delivery.BannerFormatter
import ph.bulig.mesh.delivery.ConnectivityState

/**
 * The persistent connectivity banner — the most important element in the app.
 *
 * Always visible, never collapsed, never dismissible, never behind a menu. Six
 * states, each changing icon, rule colour, tint, label and sentence *together*,
 * so no state is a recolour of another and the set stays distinguishable in
 * greyscale and by shape alone.
 *
 * Two pairs share a hue by design — OFFLINE/PENDING and SYNCING/RELAYED — and
 * are separated by icon and wording instead. `DeliveryHonestyTest` in
 * `:core-mesh` fails if a future edit lets either pair collapse into one look.
 *
 * All words come from [BannerFormatter]. This composable chooses none of them.
 *
 * @see docs/design/HANDOFF.md — "The connectivity banner"
 */
@Composable
fun ConnectivityBanner(
    state: ConnectivityState,
    count: Int = 0,
    modifier: Modifier = Modifier,
) {
    val presentation = BannerFormatter.present(state, count)
    val accent = accentFor(state)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(accent.copy(alpha = 0.10f))
            // The whole banner reads as one announcement rather than four
            // fragments a screen reader would recite separately.
            .clearAndSetSemantics {
                contentDescription = "${presentation.eyebrow}. ${presentation.sentence}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The left rule is the fastest signal in the layout: its colour and
        // position register before any text does.
        Box(
            modifier = Modifier
                .width(BuligDimens.BannerRule)
                .fillMaxHeight()
                .background(accent),
        )

        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(BuligColors.Surface, RoundedCornerShape(BuligDimens.InnerRadius))
                    .border(1.dp, accent, RoundedCornerShape(BuligDimens.InnerRadius)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = iconFor(presentation.icon),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(21.dp),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = presentation.eyebrow,
                    color = darkenForText(accent),
                    fontSize = BuligType.BannerLabel,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.2.sp,
                )
                Text(
                    text = presentation.sentence,
                    color = BuligColors.Ink,
                    fontSize = BuligType.BannerSentence,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private fun accentFor(state: ConnectivityState): Color = when (state) {
    ConnectivityState.ONLINE, ConnectivityState.SYNCED -> BuligColors.StateOnline
    ConnectivityState.OFFLINE, ConnectivityState.PENDING -> BuligColors.StateOffline
    ConnectivityState.SYNCING, ConnectivityState.RELAYED -> BuligColors.StateSyncing
}

/**
 * The formatter names icons as ligatures, so the mapping to a platform icon set
 * lives here. The shape distinctions are the point: they are what keeps two
 * states sharing a hue apart.
 */
private fun iconFor(name: String): ImageVector = when (name) {
    "cloud_done" -> Icons.Filled.CloudDone
    "signal_cellular_off" -> Icons.Filled.SignalCellularOff
    "progress_activity" -> Icons.Filled.Sync
    "hourglass_top" -> Icons.Filled.HourglassTop
    "hub" -> Icons.Filled.Hub
    "check_circle" -> Icons.Filled.CheckCircle
    else -> Icons.Filled.SignalCellularOff
}

/** The tints are light, so eyebrow text is darkened to hold AA contrast. */
private fun darkenForText(colour: Color): Color = Color(
    red = colour.red * 0.72f,
    green = colour.green * 0.72f,
    blue = colour.blue * 0.72f,
    alpha = colour.alpha,
)

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun ConnectivityBannerPreview() {
    BuligTheme {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ConnectivityBanner(ConnectivityState.OFFLINE)
            ConnectivityBanner(ConnectivityState.PENDING, count = 3)
            ConnectivityBanner(ConnectivityState.SYNCING, count = 3)
            ConnectivityBanner(ConnectivityState.RELAYED, count = 3)
            ConnectivityBanner(ConnectivityState.ONLINE)
            ConnectivityBanner(ConnectivityState.SYNCED)
        }
    }
}
