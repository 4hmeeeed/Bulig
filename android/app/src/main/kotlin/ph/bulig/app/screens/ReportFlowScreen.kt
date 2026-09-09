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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ph.bulig.app.components.BilingualLabel
import ph.bulig.app.components.Eyebrow
import ph.bulig.app.components.FooterNote
import ph.bulig.app.components.Panel
import ph.bulig.app.components.PriorityChip
import ph.bulig.app.components.PrimaryButton
import ph.bulig.app.components.SecondaryButton
import ph.bulig.app.components.StepHeader
import ph.bulig.app.components.emergencyIcon
import ph.bulig.app.theme.BuligColors
import ph.bulig.app.theme.BuligDimens
import ph.bulig.app.theme.BuligType
import ph.bulig.data.presentation.CountField
import ph.bulig.data.presentation.EmergencyTypeOption
import ph.bulig.data.presentation.ReportFlowState
import ph.bulig.data.presentation.ReportStep
import ph.bulig.data.presentation.StepperState

/**
 * Artboards 02 to 06 — the whole report flow.
 *
 * One composable dispatching on [ReportFlowState.step], because the state is one
 * object and splitting it across five navigation destinations would mean five
 * places that could disagree about the draft. The reducer in `:data` owns every
 * decision here; this file only draws.
 */
@Composable
fun ReportFlowScreen(
    state: ReportFlowState,
    onSelectType: (String) -> Unit,
    onAdjust: (CountField, Int) -> Unit,
    onDescription: (String) -> Unit,
    onLifeThreatening: (Boolean) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onJumpTo: (ReportStep) -> Unit,
    onSubmit: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.step == ReportStep.SUBMITTED) {
        SubmittedStep(state = state, onDone = onDone, modifier = modifier)
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        StepHeader(
            title = when (state.step) {
                ReportStep.TYPE -> "What is happening?"
                ReportStep.DETAILS -> "Who needs help?"
                ReportStep.LOCATION -> "Where are you?"
                else -> "Check before sending"
            },
            stepLabel = state.step.label,
            onBack = if (state.step == ReportStep.TYPE) null else onBack,
        )

        ConnectivityNote(state.connectivityNote, state.isOnline)

        Box(modifier = Modifier.weight(1f)) {
            when (state.step) {
                ReportStep.TYPE -> TypeStep(state, onSelectType)
                ReportStep.DETAILS -> DetailsStep(state, onAdjust, onDescription, onLifeThreatening)
                ReportStep.LOCATION -> LocationStep(state)
                else -> ReviewStep(state, onJumpTo)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BuligColors.Surface)
                .padding(BuligDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(BuligDimens.GapSmall),
        ) {
            PrimaryButton(
                text = if (state.step == ReportStep.REVIEW) "Send report" else "Continue",
                onClick = if (state.step == ReportStep.REVIEW) onSubmit else onNext,
                enabled = state.canContinue,
                // Never green. Green is reserved for confirmed delivery, and
                // nothing has been delivered at the moment this is tapped.
                container = if (state.step == ReportStep.REVIEW) {
                    BuligColors.StateDanger
                } else {
                    BuligColors.BrandStrong
                },
            )
            FooterNote(state.footerNote)
        }
    }
}

/** The offline strip. Matter-of-fact: offline is this app's normal case. */
@Composable
private fun ConnectivityNote(text: String, isOnline: Boolean) {
    val accent = if (isOnline) BuligColors.StateSyncing else BuligColors.StateOffline

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.10f))
            .padding(horizontal = BuligDimens.ScreenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BuligDimens.GapSmall),
    ) {
        Icon(
            imageVector = Icons.Filled.Smartphone,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(16.dp),
        )
        Text(text = text, color = BuligColors.Ink, fontSize = BuligType.BannerSentence)
    }
}

// --- 02: type ------------------------------------------------------------

@Composable
private fun TypeStep(state: ReportFlowState, onSelect: (String) -> Unit) {
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(BuligDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(BuligDimens.Gap),
    ) {
        items(state.types, key = { it.code }) { type ->
            TypeTile(
                type = type,
                selected = type.code == state.draft.typeCode,
                onClick = { onSelect(type.code) },
            )
        }
    }
}

/**
 * One type, as a full-width row rather than a grid cell.
 *
 * The design's grid was reworked into rows here for one reason: a grid cell wide
 * enough for "Nakukulong nga tawo" at 12.5sp is not wide enough on a 320dp
 * screen, and truncating the Waray would leave the resident reading only the
 * language they may not read.
 */
@Composable
private fun TypeTile(type: EmergencyTypeOption, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(BuligDimens.PanelRadius)
    val border = if (selected) BuligColors.Brand else BuligColors.Border
    val fill = if (selected) BuligColors.BrandSoft else BuligColors.Surface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(fill, shape)
            .border(
                if (selected) BuligDimens.BorderWidth else BuligDimens.HairlineWidth,
                border,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(BuligDimens.CardPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BuligDimens.GapLarge),
    ) {
        Icon(
            imageVector = emergencyIcon(type.icon),
            contentDescription = null,
            tint = if (selected) BuligColors.BrandStrong else BuligColors.InkMuted,
            modifier = Modifier.size(28.dp),
        )

        BilingualLabel(
            english = type.labelEn,
            waray = type.labelWar,
            modifier = Modifier.weight(1f),
        )

        if (selected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Selected",
                tint = BuligColors.BrandStrong,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

// --- 03: details ---------------------------------------------------------

@Composable
private fun DetailsStep(
    state: ReportFlowState,
    onAdjust: (CountField, Int) -> Unit,
    onDescription: (String) -> Unit,
    onLifeThreatening: (Boolean) -> Unit,
) {
    val fields = listOf(
        CountField.AFFECTED,
        CountField.CHILDREN,
        CountField.ELDERLY,
        CountField.MOBILITY_LIMITED,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(BuligDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(BuligDimens.Gap),
    ) {
        Eyebrow("How many people")

        Panel {
            state.steppers.forEachIndexed { index, stepper ->
                StepperRow(
                    stepper = stepper,
                    onDecrement = { onAdjust(fields[index], -1) },
                    onIncrement = { onAdjust(fields[index], +1) },
                )
            }
        }

        LifeThreateningToggle(state.draft.isLifeThreatening, onLifeThreatening)

        Eyebrow("Anything else? (optional)")
        OutlinedTextField(
            value = state.draft.description.orEmpty(),
            onValueChange = onDescription,
            placeholder = {
                Text(
                    "Describe what you can see.",
                    color = BuligColors.InkSubtle,
                    fontSize = BuligType.Body,
                )
            },
            minLines = 3,
            shape = RoundedCornerShape(BuligDimens.InnerRadius),
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = "You can leave this blank. A report with only a type still reaches the barangay.",
            color = BuligColors.InkMuted,
            fontSize = BuligType.BodySmall,
        )
    }
}

/**
 * A stepper with the minus button **disabled at the minimum, never hidden**.
 *
 * Hiding it would reflow the row under a thumb that is already moving, which is
 * how a frightened person taps the wrong control.
 */
@Composable
private fun StepperRow(
    stepper: StepperState,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BilingualLabel(
            english = stepper.labelEn,
            waray = stepper.labelWar,
            modifier = Modifier.weight(1f),
        )

        StepperButton(
            icon = Icons.Filled.Remove,
            description = "One fewer ${stepper.labelEn}",
            enabled = stepper.canDecrement,
            onClick = onDecrement,
        )

        Text(
            text = stepper.value.toString(),
            color = BuligColors.Ink,
            fontSize = BuligType.StepperValue,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.size(width = 52.dp, height = 32.dp),
        )

        StepperButton(
            icon = Icons.Filled.Add,
            description = "One more ${stepper.labelEn}",
            enabled = stepper.canIncrement,
            onClick = onIncrement,
        )
    }
}

@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(BuligDimens.InnerRadius)
    val tint = if (enabled) BuligColors.BrandStrong else BuligColors.InkSubtle

    Box(
        modifier = Modifier
            .size(BuligDimens.StepperButton)
            .background(
                if (enabled) BuligColors.BrandSoft else BuligColors.Canvas,
                shape,
            )
            .border(BuligDimens.HairlineWidth, BuligColors.Border, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = tint)
    }
}

/**
 * The single highest-weight input in the priority engine: 25 points flat.
 *
 * Given its own panel and its own explanation rather than sitting among the
 * steppers, because a resident toggling it should understand they are telling
 * the barangay someone may die.
 */
@Composable
private fun LifeThreateningToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(BuligDimens.PanelRadius)
    val accent = if (checked) BuligColors.StateDanger else BuligColors.Border

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (checked) BuligColors.StateDanger.copy(alpha = 0.07f) else BuligColors.Surface,
                shape,
            )
            .border(if (checked) BuligDimens.BorderWidth else BuligDimens.HairlineWidth, accent, shape)
            .clickable { onChange(!checked) }
            .padding(BuligDimens.CardPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BuligDimens.Gap),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Someone's life is in danger",
                color = BuligColors.Ink,
                fontSize = BuligType.ListRowTitle,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Delikado an kinabuhi",
                color = BuligColors.InkMuted,
                fontSize = BuligType.BilingualSubtitle,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Text(
                text = "This moves your report to the top of the barangay's list.",
                color = BuligColors.InkMuted,
                fontSize = BuligType.BodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BuligColors.InkInverse,
                checkedTrackColor = BuligColors.StateDanger,
            ),
        )
    }
}

// --- 04: location --------------------------------------------------------

@Composable
private fun LocationStep(state: ReportFlowState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(BuligDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(BuligDimens.Gap),
    ) {
        Panel {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BuligDimens.GapSmall),
            ) {
                Icon(
                    imageVector = Icons.Filled.LocationOn,
                    contentDescription = null,
                    tint = if (state.hasCoordinates) BuligColors.BrandStrong else BuligColors.StateOffline,
                )
                Text(
                    text = if (state.hasCoordinates) "Location found" else "No location yet",
                    color = BuligColors.Ink,
                    fontSize = BuligType.SectionHeading,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (state.hasCoordinates) {
                Text(
                    text = "%.5f, %.5f".format(state.draft.latitude, state.draft.longitude),
                    color = BuligColors.InkMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = BuligType.MonoMeta,
                )
                state.locationAccuracyM?.let {
                    Text(
                        text = "Accurate to about ${it.toInt()} metres.",
                        color = BuligColors.InkMuted,
                        fontSize = BuligType.BodySmall,
                    )
                }
            } else {
                // Never a blocker. A report with no coordinates still reaches
                // the barangay, and refusing to file one would be worse than
                // filing one that needs a follow-up question.
                Text(
                    text = "Your phone has not found a GPS fix. You can still send this " +
                        "report — the barangay will see it without a pin on the map.",
                    color = BuligColors.InkMuted,
                    fontSize = BuligType.Body,
                )
            }
        }

        state.purok?.let {
            Panel {
                Eyebrow("Purok")
                Text(text = it, color = BuligColors.Ink, fontSize = BuligType.ListRowTitle)
            }
        }

        Text(
            text = "GPS capture is not wired up in this build, so this screen shows what " +
                "the flow will report rather than a live fix.",
            color = BuligColors.InkSubtle,
            fontSize = BuligType.BodySmall,
        )
    }
}

// --- 05: review ----------------------------------------------------------

@Composable
private fun ReviewStep(state: ReportFlowState, onJumpTo: (ReportStep) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(BuligDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(BuligDimens.Gap),
    ) {
        ReviewRow(
            label = "Emergency",
            value = state.selectedType?.labelEn ?: "Not chosen",
            onChange = { onJumpTo(ReportStep.TYPE) },
        )

        ReviewRow(
            label = "People",
            value = buildString {
                append("${state.draft.affectedCount} affected")
                if (state.draft.childrenCount > 0) append(", ${state.draft.childrenCount} children")
                if (state.draft.elderlyCount > 0) append(", ${state.draft.elderlyCount} elderly")
                if (state.draft.mobilityLimitedCount > 0) {
                    append(", ${state.draft.mobilityLimitedCount} cannot walk alone")
                }
            },
            onChange = { onJumpTo(ReportStep.DETAILS) },
        )

        ReviewRow(
            label = "Location",
            value = if (state.hasCoordinates) "Pin set" else "No GPS fix",
            onChange = { onJumpTo(ReportStep.LOCATION) },
        )

        state.priority?.let { PriorityExplanation(it) }
    }
}

@Composable
private fun ReviewRow(label: String, value: String, onChange: () -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Eyebrow(label)
                Text(
                    text = value,
                    color = BuligColors.Ink,
                    fontSize = BuligType.ListRowTitle,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                text = "Change",
                color = BuligColors.BrandStrong,
                fontSize = BuligType.BodySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(onClick = onChange)
                    .padding(BuligDimens.GapSmall),
            )
        }
    }
}

/**
 * Why this report scored what it did, in the resident's own terms.
 *
 * The proposal rules out AI for exactly this: a resident is entitled to know why
 * their neighbour's report is being answered first, and a score nobody can
 * explain is not something a barangay can defend.
 */
@Composable
private fun PriorityExplanation(priority: ph.bulig.mesh.priority.PriorityResult) {
    Panel {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BuligDimens.GapSmall),
        ) {
            Eyebrow("Urgency")
            PriorityChip(priority.level)
        }

        priority.reasons().forEach { reason ->
            Row(horizontalArrangement = Arrangement.spacedBy(BuligDimens.GapSmall)) {
                Text("•", color = BuligColors.InkSubtle, fontSize = BuligType.Body)
                Text(text = reason, color = BuligColors.InkMuted, fontSize = BuligType.BodySmall)
            }
        }

        Text(
            text = "The barangay decides what happens next. This ordering is a " +
                "suggestion, not a decision.",
            color = BuligColors.InkSubtle,
            fontSize = BuligType.BodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// --- 06: submitted -------------------------------------------------------

/**
 * The confirmation.
 *
 * The hardest screen in the app to get right, because every instinct says to
 * show a green tick and the word "Sent". Nothing has been sent. The report is on
 * this phone. Saying otherwise would be the single most damaging lie the app
 * could tell, so this screen is deliberately neutral-toned and says exactly what
 * happened: saved here, and it will travel on its own.
 */
@Composable
private fun SubmittedStep(
    state: ReportFlowState,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(BuligDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(BuligDimens.GapLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(40.dp))

        Icon(
            imageVector = Icons.Filled.Smartphone,
            contentDescription = null,
            tint = BuligColors.StateLocal,
            modifier = Modifier.size(56.dp),
        )

        Text(
            text = "Saved on your phone",
            color = BuligColors.Ink,
            fontSize = BuligType.ConfirmTitle,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Text(
            text = "Your report has not reached the barangay yet. Your phone will pass it " +
                "to other phones nearby, and it will keep trying on its own. You can " +
                "close the app.",
            color = BuligColors.InkMuted,
            fontSize = BuligType.Body,
            textAlign = TextAlign.Center,
        )

        state.emergencyCode?.let { code ->
            Panel {
                Eyebrow("Show this code to the barangay")
                Text(
                    text = code,
                    color = BuligColors.Ink,
                    fontSize = BuligType.EmergencyCode,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        SecondaryButton(text = "Back to home", onClick = onDone)
    }
}
