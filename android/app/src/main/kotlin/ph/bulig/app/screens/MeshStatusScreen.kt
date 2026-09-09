package ph.bulig.app.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.bulig.app.components.Eyebrow
import ph.bulig.app.components.StepHeader
import ph.bulig.app.theme.BuligColors
import ph.bulig.app.theme.BuligDimens
import ph.bulig.app.theme.BuligType
import ph.bulig.data.presentation.MeshStatusState
import ph.bulig.data.presentation.NearbyPeer
import ph.bulig.data.presentation.PeerRole

/**
 * Artboard 09 — the emotional core.
 *
 * The headline sentence comes first and is not a stat tile. The design is
 * explicit about that: this screen exists to tell a resident their phone is
 * helping their neighbours, not to report telemetry at them.
 *
 * Every number rendered here was derived by `MeshStatusStateFactory`, which is
 * tested. This file chooses no words.
 */
@Composable
fun MeshStatusScreen(
    state: MeshStatusState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BuligColors.Canvas),
    ) {
        StepHeader(title = "Bulig mesh", stepLabel = "", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(BuligDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(BuligDimens.Gap),
        ) {
            RadarHero(count = state.nearbyCount, rangeNote = state.rangeNote, active = state.isActive)

            HeadlineCallout(headline = state.headline, reassurance = state.reassurance)

            Row(horizontalArrangement = Arrangement.spacedBy(BuligDimens.Gap)) {
                ContributionTile(
                    value = state.passedOnToday,
                    label = "Reports passed on today",
                    modifier = Modifier.weight(1f),
                )
                ContributionTile(
                    value = state.deliveredBecauseOfYou,
                    label = "Delivered because of you",
                    modifier = Modifier.weight(1f),
                )
            }

            if (state.hasPeers) {
                Eyebrow("Nearby devices")
                state.peers.forEach { PeerRow(it) }
            }

            state.churnNote?.let {
                Text(
                    text = it,
                    color = BuligColors.InkSubtle,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }

            Text(
                text = state.privacyNote,
                color = BuligColors.InkSubtle,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

/**
 * The pulsing radar.
 *
 * Deliberately abstract: concentric rings and anonymous dots, never a map of
 * where the neighbours are. The app does not know their positions, and drawing
 * something that looks like it does would be the same class of lie as a
 * premature delivery tick.
 */
@Composable
private fun RadarHero(count: Int, rangeNote: String, active: Boolean) {
    val shape = RoundedCornerShape(BuligDimens.PanelRadius)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(158.dp)
            .background(BuligColors.BrandStrong, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            PulseRing(delayMillis = 0)
            PulseRing(delayMillis = 1700)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = count.toString(),
                color = BuligColors.InkInverse,
                fontSize = 46.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = "BULIG PHONES NEARBY",
                color = BuligColors.InkInverse,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            )
            // Honest about range. The rings are decoration; this line is the fact.
            Text(
                text = rangeNote,
                color = BuligColors.InkInverse.copy(alpha = 0.75f),
                fontSize = 12.5.sp,
            )
        }

        if (active) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.BluetoothConnected,
                    contentDescription = null,
                    tint = BuligColors.InkInverse,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "ACTIVE",
                    color = BuligColors.InkInverse,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun PulseRing(delayMillis: Int) {
    val transition = rememberInfiniteTransition(label = "pulse")

    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3400, delayMillis = delayMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ring",
    )

    Box(
        modifier = Modifier
            .size(118.dp)
            .scale(0.55f + progress * 0.75f)
            .border(
                width = 1.5.dp,
                color = Color.White.copy(alpha = 0.45f * (1f - progress)),
                shape = RoundedCornerShape(percent = 50),
            )
    )
}

/**
 * The sentence the screen is built around.
 *
 * Given the most visual weight after the radar, and kept as prose. Turning it
 * into a stat tile is what the design explicitly warns against.
 */
@Composable
private fun HeadlineCallout(headline: String, reassurance: String) {
    val shape = RoundedCornerShape(BuligDimens.PanelRadius)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BuligColors.BrandSoft, shape)
            .border(1.dp, BuligColors.Brand.copy(alpha = 0.35f), shape)
            .padding(BuligDimens.CardPadding),
        horizontalArrangement = Arrangement.spacedBy(BuligDimens.Gap),
    ) {
        Icon(
            imageVector = Icons.Filled.VolunteerActivism,
            contentDescription = null,
            tint = BuligColors.BrandStrong,
            modifier = Modifier.size(26.dp),
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = headline,
                color = BuligColors.Ink,
                fontSize = 17.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = reassurance,
                color = BuligColors.InkMuted,
                fontSize = BuligType.BodySmall,
                lineHeight = 19.sp,
            )
        }
    }
}

@Composable
private fun ContributionTile(value: Int, label: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(BuligDimens.PanelRadius)

    Column(
        modifier = modifier
            .background(BuligColors.Surface, shape)
            .border(BuligDimens.HairlineWidth, BuligColors.Border, shape)
            .padding(BuligDimens.CardPadding),
    ) {
        Text(
            text = value.toString(),
            color = BuligColors.Ink,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            color = BuligColors.InkMuted,
            fontSize = 12.sp,
            lineHeight = 15.sp,
        )
    }
}

/**
 * One nearby device.
 *
 * The role chip is the only green on this screen, and it is earned: a peer
 * reporting internet is confirmed connected, which is exactly what the reserved
 * green means everywhere else in the app.
 */
@Composable
private fun PeerRow(peer: NearbyPeer) {
    val shape = RoundedCornerShape(BuligDimens.InnerRadius)
    val accent = if (peer.role == PeerRole.CAN_UPLOAD) {
        BuligColors.StateOnline
    } else {
        BuligColors.StateSyncing
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BuligColors.Surface, shape)
            .border(BuligDimens.HairlineWidth, BuligColors.Border, shape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BuligDimens.GapSmall),
    ) {
        Icon(
            imageVector = Icons.Filled.Smartphone,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = peer.pseudonym,
                color = BuligColors.Ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = peer.description,
                color = BuligColors.InkMuted,
                fontSize = 12.sp,
            )
        }

        Text(
            text = peer.role.label,
            color = accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier
                .background(accent.copy(alpha = 0.10f), RoundedCornerShape(BuligDimens.ChipRadius))
                .padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}
