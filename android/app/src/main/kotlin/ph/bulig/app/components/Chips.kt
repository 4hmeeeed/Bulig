package ph.bulig.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Square
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.bulig.app.theme.BuligColors
import ph.bulig.app.theme.BuligDimens
import ph.bulig.app.theme.BuligTheme
import ph.bulig.app.theme.BuligType
import ph.bulig.mesh.delivery.DeliveryFormatter
import ph.bulig.mesh.delivery.DeliveryState
import ph.bulig.mesh.delivery.DeliveryTone
import ph.bulig.mesh.priority.PriorityLevel

/**
 * Priority as colour **plus** label **plus** a distinct icon silhouette.
 *
 * The four shapes are deliberately different outlines — octagon, triangle,
 * square, circle — not four colours of the same dot. Barangay offices print duty
 * rosters on mono laser printers, and a colour-blind operator has to read this
 * at a glance during an emergency. Colour alone would fail both.
 */
@Composable
fun PriorityChip(
    level: PriorityLevel,
    modifier: Modifier = Modifier,
) {
    val (fill, content) = when (level) {
        PriorityLevel.CRITICAL -> BuligColors.PriorityCritical to BuligColors.InkInverse
        PriorityLevel.HIGH -> BuligColors.PriorityHigh to BuligColors.OnPriorityHigh
        PriorityLevel.MODERATE -> BuligColors.PriorityModerate to BuligColors.OnPriorityModerate
        PriorityLevel.LOW -> BuligColors.PriorityLow to BuligColors.InkInverse
    }

    val shape = when (level) {
        PriorityLevel.CRITICAL -> Icons.Filled.Error          // octagon
        PriorityLevel.HIGH -> Icons.Filled.ChangeHistory      // triangle
        PriorityLevel.MODERATE -> Icons.Filled.Square         // square
        PriorityLevel.LOW -> Icons.Filled.Circle              // circle
    }

    Chip(
        label = level.name,
        icon = shape,
        background = fill,
        contentColour = content,
        modifier = modifier,
    )
}

/**
 * A report's delivery state.
 *
 * Grey means it is only on this phone. Blue means copies are travelling and it
 * is explicitly *not* delivered. Green means the command center confirmed
 * receipt — and green appears nowhere else.
 *
 * Both the words and the tone come from [DeliveryFormatter], so this composable
 * cannot pair a green tick with "not yet delivered" even by mistake.
 */
@Composable
fun DeliveryChip(
    state: DeliveryState,
    hopCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    val presentation = DeliveryFormatter.present(state, hopCount)
    val accent = when (presentation.tone) {
        DeliveryTone.NEUTRAL -> BuligColors.StateLocal
        DeliveryTone.IN_MOTION -> BuligColors.StateSyncing
        DeliveryTone.CONFIRMED -> BuligColors.StateOnline
    }

    Chip(
        label = presentation.label,
        icon = deliveryIcon(presentation.icon),
        // Bordered and tinted rather than filled, so a delivery chip never
        // competes with the priority chip beside it.
        background = accent.copy(alpha = 0.10f),
        contentColour = accent,
        borderColour = accent.copy(alpha = 0.45f),
        modifier = modifier,
    )
}

@Composable
private fun Chip(
    label: String,
    icon: ImageVector,
    background: Color,
    contentColour: Color,
    modifier: Modifier = Modifier,
    borderColour: Color? = null,
) {
    val shape = RoundedCornerShape(BuligDimens.ChipRadius)

    Row(
        modifier = modifier
            .background(background, shape)
            .then(if (borderColour != null) Modifier.border(1.dp, borderColour, shape) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            // The label says the same thing; announcing the icon too would make
            // a screen reader repeat itself.
            contentDescription = null,
            tint = contentColour,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = label,
            color = contentColour,
            fontSize = BuligType.ChipLabel,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
    }
}

private fun deliveryIcon(name: String): ImageVector = when (name) {
    "smartphone" -> Icons.Filled.Smartphone
    "hub" -> Icons.Filled.Hub
    "check_circle" -> Icons.Filled.CheckCircle
    "badge" -> Icons.Filled.Badge
    "directions_walk" -> Icons.Filled.DirectionsWalk
    "location_on" -> Icons.Filled.LocationOn
    "task_alt" -> Icons.Filled.TaskAlt
    else -> Icons.Filled.Smartphone
}

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun ChipsPreview() {
    BuligTheme {
        androidx.compose.foundation.layout.Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PriorityLevel.entries.forEach { PriorityChip(it) }
            }
            DeliveryChip(DeliveryState.SAVED_LOCAL)
            DeliveryChip(DeliveryState.RELAYED, hopCount = 3)
            DeliveryChip(DeliveryState.DELIVERED)
            DeliveryChip(DeliveryState.RESOLVED)
        }
    }
}
