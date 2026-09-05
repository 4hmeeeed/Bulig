package ph.bulig.app.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ph.bulig.app.theme.BuligColors
import ph.bulig.app.theme.BuligDimens
import ph.bulig.app.theme.BuligType

/**
 * The header on every step of the report flow.
 *
 * Carries the step count because the design shows "2 / 4" throughout: a
 * frightened person needs to know how much is left, and a flow with no visible
 * end feels endless.
 */
@Composable
fun StepHeader(
    title: String,
    stepLabel: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(BuligDimens.BackHeaderHeight)
            .padding(horizontal = BuligDimens.ScreenPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .size(BuligDimens.TouchTarget)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Go back",
                    tint = BuligColors.Ink,
                )
            }
        }

        Text(
            text = title,
            color = BuligColors.Ink,
            fontSize = BuligType.ScreenTitle,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )

        if (stepLabel.isNotEmpty()) {
            Text(
                text = stepLabel,
                color = BuligColors.InkSubtle,
                fontSize = BuligType.BodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * The one action that moves the flow forward.
 *
 * Fixed at [BuligDimens.PrimaryButtonHeight] — 60dp, well above the 48dp floor.
 * The person tapping it may be one-handed, in rain, with wet hands.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = BuligColors.BrandStrong,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(BuligDimens.InnerRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = BuligColors.InkInverse,
            disabledContainerColor = BuligColors.Border,
            disabledContentColor = BuligColors.InkSubtle,
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(BuligDimens.PrimaryButtonHeight),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BuligDimens.GapSmall),
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    // The label says the same thing; announcing both would make
                    // a screen reader repeat itself.
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(text = text, fontSize = BuligType.Body, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(BuligDimens.InnerRadius),
        border = BorderStroke(BuligDimens.HairlineWidth, BuligColors.Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = BuligColors.Ink),
        modifier = modifier
            .fillMaxWidth()
            .height(BuligDimens.SecondaryButtonHeight),
    ) {
        Text(text = text, fontSize = BuligType.BodySmall, fontWeight = FontWeight.Medium)
    }
}

/** The white card everything sits on. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(BuligDimens.PanelRadius)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BuligColors.Surface, shape)
            .border(BuligDimens.HairlineWidth, BuligColors.Border, shape)
            .padding(BuligDimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(BuligDimens.GapSmall),
        content = content,
    )
}

/**
 * The reassurance line under the footer button.
 *
 * Deliberately not styled as a warning. "Nothing is sent yet" is a statement of
 * fact about an offline-first app, not a problem the resident must solve.
 */
@Composable
fun FooterNote(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return

    Text(
        text = text,
        color = BuligColors.InkMuted,
        fontSize = BuligType.BodySmall,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * An English line with its Waray translation beneath.
 *
 * The Waray is marked [clearAndSetSemantics] with nothing, so a screen reader
 * announces the pair once rather than reading the same instruction twice in two
 * languages.
 */
@Composable
fun BilingualLabel(
    english: String,
    waray: String?,
    modifier: Modifier = Modifier,
    englishSize: androidx.compose.ui.unit.TextUnit = BuligType.ListRowTitle,
) {
    Column(modifier = modifier) {
        Text(
            text = english,
            color = BuligColors.Ink,
            fontSize = englishSize,
            fontWeight = FontWeight.Medium,
        )
        if (!waray.isNullOrBlank()) {
            Text(
                text = waray,
                color = BuligColors.InkMuted,
                fontSize = BuligType.BilingualSubtitle,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

/** Small all-caps heading above a group. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = BuligColors.InkSubtle,
        fontSize = BuligType.Eyebrow,
        fontWeight = FontWeight.Bold,
        modifier = modifier,
    )
}
