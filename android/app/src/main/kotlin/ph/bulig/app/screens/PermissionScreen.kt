package ph.bulig.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.bulig.app.components.Panel
import ph.bulig.app.components.PrimaryButton
import ph.bulig.app.components.SecondaryButton
import ph.bulig.app.theme.BuligColors
import ph.bulig.app.theme.BuligDimens
import ph.bulig.app.theme.BuligType
import ph.bulig.data.presentation.MeshCapability
import ph.bulig.data.presentation.MeshPermission
import ph.bulig.data.presentation.PermissionUiState

/**
 * The permission rationale, shown before Android's own terse dialog.
 *
 * Android asks "Allow Bulig to find nearby devices?" and gives no reason. This
 * screen supplies the reason, and — if the resident declines — the honest
 * consequence, which is the part most apps skip.
 *
 * It is never a gate. "Not now" always works, because a phone with no
 * permissions can still save a report locally, and refusing to let somebody file
 * one would be a worse failure than relaying nothing.
 *
 * Every word comes from `PermissionStateFactory`, which is tested.
 */
@Composable
fun PermissionScreen(
    state: PermissionUiState,
    onRequest: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BuligColors.Canvas)
            .padding(BuligDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(BuligDimens.Gap),
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Icon(
            imageVector = Icons.Filled.BluetoothSearching,
            contentDescription = null,
            tint = BuligColors.BrandStrong,
            modifier = Modifier
                .size(52.dp)
                .align(Alignment.CenterHorizontally),
        )

        Text(
            text = state.headline,
            color = BuligColors.Ink,
            fontSize = BuligType.ConfirmTitle,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = state.explanation,
            color = BuligColors.InkMuted,
            fontSize = BuligType.Body,
            lineHeight = 23.sp,
            textAlign = TextAlign.Center,
        )

        Panel {
            PermissionRow(
                label = "Find nearby phones",
                granted = state.granted.contains(MeshPermission.FIND_NEARBY_PHONES),
            )
            PermissionRow(
                label = "Let other phones find yours",
                granted = state.granted.contains(MeshPermission.BE_FOUND_BY_OTHERS),
            )
            PermissionRow(
                label = "Exchange reports",
                granted = state.granted.contains(MeshPermission.EXCHANGE_REPORTS),
            )
        }

        state.consequence?.let { ConsequenceNote(text = it, capability = state.capability) }

        Spacer(modifier = Modifier.weight(1f))

        PrimaryButton(text = state.primaryLabel, onClick = onRequest)

        // Always present. The permission screen is not a gate.
        if (!state.isFullyGranted) {
            SecondaryButton(text = "Not now", onClick = onSkip)
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BuligDimens.GapSmall),
    ) {
        Icon(
            // Green only where the permission is genuinely held — the same
            // reserved-green rule the delivery states follow.
            imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = if (granted) "Granted" else "Not granted",
            tint = if (granted) BuligColors.StateOnline else BuligColors.InkSubtle,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            color = if (granted) BuligColors.Ink else BuligColors.InkMuted,
            fontSize = BuligType.ListRowTitle,
        )
    }
}

/**
 * What the phone can no longer do.
 *
 * Amber rather than red: a resident who declined has not made an error, and
 * styling their choice as one would be scolding somebody who is entitled to say
 * no to a radio permission.
 */
@Composable
private fun ConsequenceNote(text: String, capability: MeshCapability) {
    val accent = when (capability) {
        MeshCapability.FULL -> BuligColors.StateSyncing
        else -> BuligColors.StateOffline
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.10f))
            .padding(BuligDimens.CardPadding),
    ) {
        Text(
            text = text,
            color = BuligColors.Ink,
            fontSize = BuligType.BodySmall,
            lineHeight = 20.sp,
        )
    }
}
