package ph.bulig.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.bulig.app.components.Eyebrow
import ph.bulig.app.components.Panel
import ph.bulig.app.components.PriorityChip
import ph.bulig.app.components.PrimaryButton
import ph.bulig.app.components.SecondaryButton
import ph.bulig.app.components.StepHeader
import ph.bulig.app.components.emergencyIcon
import ph.bulig.app.theme.BuligColors
import ph.bulig.app.theme.BuligDimens
import ph.bulig.app.theme.BuligType
import ph.bulig.data.presentation.ActionBarState
import ph.bulig.data.presentation.ActionTone
import ph.bulig.data.presentation.AssignmentDetailState
import ph.bulig.data.presentation.AssignmentListState
import ph.bulig.data.presentation.AssignmentRow
import ph.bulig.data.presentation.VulnerabilityTile

/**
 * Artboard 10 — what a responder answers while walking: what, how bad, how far,
 * how old.
 *
 * The ordering is the factory's and is not reorderable here. A responder under
 * pressure must not be able to bury a CRITICAL by dragging it, and the ordering
 * a barangay defends afterwards has to be the one the rules produced.
 */
@Composable
fun AssignmentListScreen(
    state: AssignmentListState,
    onOpen: (AssignmentRow) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BuligColors.Canvas),
    ) {
        StepHeader(title = "My assignments", stepLabel = "", onBack = onBack)

        Text(
            text = listOfNotNull(state.responderName, state.zone).joinToString(" · "),
            color = BuligColors.InkMuted,
            fontSize = BuligType.ScreenSubtitle,
            modifier = Modifier.padding(horizontal = BuligDimens.ScreenPadding),
        )

        state.banner?.let { SyncingStrip(it) }

        if (state.isEmpty) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.emptyMessage, color = BuligColors.InkMuted, fontSize = BuligType.Body)
            }
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(BuligDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(BuligDimens.Gap),
        ) {
            items(state.rows, key = { it.emergencyCode }) { row ->
                AssignmentCard(row = row, onOpen = { onOpen(row) })
            }

            item {
                // Explains every age above it. Without this line a CRITICAL that
                // took twenty minutes to arrive looks like it just happened.
                Text(
                    text = state.footnote,
                    color = BuligColors.InkSubtle,
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = BuligDimens.GapSmall),
                )
            }
        }
    }
}

@Composable
private fun SyncingStrip(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BuligColors.StateSyncing.copy(alpha = 0.10f))
            .padding(horizontal = BuligDimens.ScreenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BuligDimens.GapSmall),
    ) {
        Icon(
            imageVector = Icons.Filled.Hub,
            contentDescription = null,
            tint = BuligColors.StateSyncing,
            modifier = Modifier.size(16.dp),
        )
        Text(text = text, color = BuligColors.Ink, fontSize = BuligType.BannerSentence)
    }
}

@Composable
private fun AssignmentCard(row: AssignmentRow, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(BuligDimens.PanelRadius)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BuligColors.Surface, shape)
            .border(BuligDimens.HairlineWidth, BuligColors.Border, shape)
            .clickable(onClick = onOpen)
            .padding(BuligDimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(BuligDimens.GapSmall),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PriorityChip(row.priorityLevel)

            row.statusChip?.let {
                Text(
                    text = it,
                    color = BuligColors.StateSyncing,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .background(
                            BuligColors.StateSyncing.copy(alpha = 0.10f),
                            RoundedCornerShape(BuligDimens.ChipRadius),
                        )
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }

            Text(
                text = row.ageLabel,
                color = BuligColors.InkSubtle,
                fontSize = BuligType.BodySmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = BuligDimens.GapSmall),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BuligDimens.GapSmall),
        ) {
            Icon(
                imageVector = emergencyIcon(row.assignment.typeCode.lowercase()),
                contentDescription = null,
                tint = BuligColors.Ink,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = row.typeLabelEn,
                color = BuligColors.Ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Text(
            text = row.affectedSummary,
            color = BuligColors.InkMuted,
            fontSize = BuligType.BodySmall,
            lineHeight = 20.sp,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BuligDimens.GapLarge),
        ) {
            row.distanceLabel?.let {
                IconLine(Icons.Filled.NearMe, it, BuligColors.Ink)
            }
            row.hopLabel?.let {
                IconLine(Icons.Filled.Hub, it, BuligColors.Brand)
            }
        }

        if (row.isExpanded) {
            PrimaryButton(text = "Open assignment", onClick = onOpen)
        }
    }
}

@Composable
private fun IconLine(icon: ImageVector, text: String, tint: androidx.compose.ui.graphics.Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        Text(text = text, color = tint, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Artboard 11 — everything a responder needs before deciding to accept.
 *
 * The reasoning behind the priority is shown so a responder can override it with
 * judgement, which they could not do against a number with no explanation.
 */
@Composable
fun AssignmentDetailScreen(
    state: AssignmentDetailState,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onEscalate: () -> Unit,
    onBack: () -> Unit,
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
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(BuligDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(BuligDimens.Gap),
        ) {
            Text(
                text = state.assignment.emergencyCode,
                color = BuligColors.InkSubtle,
                fontFamily = FontFamily.Monospace,
                fontSize = BuligType.MonoMeta,
            )

            state.residentWords?.let { words ->
                Panel {
                    Eyebrow("Resident's words")
                    // Verbatim. The app never machine-translates an emergency:
                    // nuance loss in a rescue description can cost lives, and a
                    // responder reading a mistranslation cannot know it happened.
                    Text(
                        text = "“$words”",
                        color = BuligColors.Ink,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                    )
                    Text(
                        text = state.residentWordsNote,
                        color = BuligColors.InkSubtle,
                        fontSize = 11.5.sp,
                    )
                }
            }

            VulnerabilityGrid(state.tiles)

            if (state.priorityReasons.isNotEmpty()) {
                Panel {
                    Eyebrow("Why this ranking")
                    state.priorityReasons.forEach { reason ->
                        Row(horizontalArrangement = Arrangement.spacedBy(BuligDimens.GapSmall)) {
                            Text("•", color = BuligColors.InkSubtle, fontSize = BuligType.Body)
                            Text(
                                text = reason,
                                color = BuligColors.InkMuted,
                                fontSize = BuligType.BodySmall,
                            )
                        }
                    }
                }
            }

            state.meshLatencyNote?.let { MeshLatencyNote(it) }
        }

        ActionBar(
            state = state.actionBar,
            onPrimary = onPrimary,
            onSecondary = onSecondary,
            onEscalate = onEscalate,
        )
    }
}

/**
 * Children, elderly and mobility-limited are tinted; the plain affected count is
 * not. Those three change what a responder *brings* — a carry, a second person,
 * a different route.
 */
@Composable
private fun VulnerabilityGrid(tiles: List<VulnerabilityTile>) {
    Column(verticalArrangement = Arrangement.spacedBy(BuligDimens.GapSmall)) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(BuligDimens.GapSmall)) {
                pair.forEach { tile ->
                    val shape = RoundedCornerShape(BuligDimens.InnerRadius)
                    val border =
                        if (tile.isCritical) BuligColors.StateDanger.copy(alpha = 0.30f)
                        else BuligColors.Border

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (tile.isCritical) {
                                    BuligColors.StateDanger.copy(alpha = 0.06f)
                                } else {
                                    BuligColors.Surface
                                },
                                shape,
                            )
                            .border(BuligDimens.HairlineWidth, border, shape)
                            .padding(BuligDimens.CardPadding),
                    ) {
                        Text(
                            text = tile.value.toString(),
                            color = BuligColors.Ink,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = tile.label,
                            color = BuligColors.InkMuted,
                            fontSize = 12.5.sp,
                            lineHeight = 15.sp,
                        )
                    }
                }

                // Keeps a lone tile at half width rather than stretching it.
                if (pair.size == 1) Column(modifier = Modifier.weight(1f)) {}
            }
        }
    }
}

@Composable
private fun MeshLatencyNote(text: String) {
    val shape = RoundedCornerShape(BuligDimens.InnerRadius)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BuligColors.StateSyncing.copy(alpha = 0.08f), shape)
            .border(BuligDimens.HairlineWidth, BuligColors.StateSyncing.copy(alpha = 0.30f), shape)
            .padding(BuligDimens.CardPadding),
        horizontalArrangement = Arrangement.spacedBy(BuligDimens.GapSmall),
    ) {
        Icon(
            imageVector = Icons.Filled.Hub,
            contentDescription = null,
            tint = BuligColors.StateSyncing,
            modifier = Modifier.size(18.dp),
        )
        Text(text = text, color = BuligColors.InkMuted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

/**
 * Artboard 12 — one decision per state.
 *
 * The next action is the only filled button on screen. A responder choosing
 * between two equally-weighted buttons in the rain is a design failure, so the
 * secondary is never styled as a competing choice.
 */
@Composable
private fun ActionBar(
    state: ActionBarState,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onEscalate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BuligColors.Surface)
            .padding(BuligDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(BuligDimens.GapSmall),
    ) {
        state.statusPill?.let { pill ->
            val tint = when (state.primaryTone) {
                // A locally-resolved job that has not uploaded is amber, not
                // green — the same rule a resident's report obeys.
                ActionTone.CLOSED -> BuligColors.StateOffline
                ActionTone.CONFIRM -> BuligColors.StateOnline
                ActionTone.ADVANCE -> BuligColors.StateSyncing
            }

            Text(
                text = pill,
                color = tint,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(tint.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }

        Row(
            modifier = Modifier.height(BuligDimens.ResponderActionHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryButton(
                text = state.primaryLabel,
                onClick = onPrimary,
                enabled = state.primaryEnabled,
                container = when (state.primaryTone) {
                    ActionTone.CONFIRM -> BuligColors.StateOnline
                    ActionTone.ADVANCE -> BuligColors.BrandStrong
                    ActionTone.CLOSED -> BuligColors.Border
                },
                icon = actionIcon(state.primaryIcon),
                modifier = Modifier.height(BuligDimens.ResponderActionHeight),
            )
        }

        state.escalateLabel?.let { SecondaryButton(text = it, onClick = onEscalate) }
        state.secondaryLabel?.let { SecondaryButton(text = it, onClick = onSecondary) }
    }
}

/** The state factory names an icon; this maps it to a drawable. */
private fun actionIcon(name: String): ImageVector = when (name) {
    "check_circle" -> Icons.Filled.CheckCircle
    "directions_walk" -> Icons.Filled.DirectionsWalk
    "location_on" -> Icons.Filled.LocationOn
    "task_alt" -> Icons.Filled.TaskAlt
    else -> Icons.Filled.CheckCircle
}
