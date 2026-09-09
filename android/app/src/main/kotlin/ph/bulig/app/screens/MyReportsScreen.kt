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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ph.bulig.app.components.ConnectivityBanner
import ph.bulig.app.components.DeliveryChip
import ph.bulig.app.components.StepHeader
import ph.bulig.app.theme.BuligColors
import ph.bulig.app.theme.BuligDimens
import ph.bulig.app.theme.BuligType
import ph.bulig.data.presentation.MyReportsState
import ph.bulig.data.presentation.ReportRowState

/**
 * Artboard 07 — every report this resident filed, and where each one has got to.
 *
 * The ordering is the factory's, not this file's: undelivered never sinks below
 * delivered, because the stuck ones are the only rows a resident can still act
 * on — by walking somewhere with more phones, or more signal.
 */
@Composable
fun MyReportsScreen(
    state: MyReportsState,
    onBack: () -> Unit,
    onOpenReport: (ReportRowState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BuligColors.Canvas),
    ) {
        StepHeader(title = "My reports", stepLabel = "", onBack = onBack)

        ConnectivityBanner(state.banner)

        if (state.isEmpty) {
            EmptyState(state.emptyMessage)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(BuligDimens.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(BuligDimens.Gap),
            ) {
                items(state.rows, key = { it.packetId.value }) { row ->
                    ReportRow(row = row, onClick = { onOpenReport(row) })
                }
            }
        }
    }
}

/**
 * One report.
 *
 * The chip is never the only explanation — the plain sentence beneath it says
 * the same thing in words, because "RELAYED" means nothing to somebody who has
 * not read the manual, and there is no manual.
 */
@Composable
private fun ReportRow(row: ReportRowState, onClick: () -> Unit) {
    val shape = RoundedCornerShape(BuligDimens.PanelRadius)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BuligColors.Surface, shape)
            .border(BuligDimens.HairlineWidth, BuligColors.Border, shape)
            .clickable(onClick = onClick)
            .padding(BuligDimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(BuligDimens.GapSmall),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.typeLabelEn,
                    color = BuligColors.Ink,
                    fontSize = BuligType.ListRowTitle,
                    fontWeight = FontWeight.SemiBold,
                )
                row.typeLabelWar?.let {
                    Text(
                        text = it,
                        color = BuligColors.InkMuted,
                        fontSize = BuligType.BilingualSubtitle,
                    )
                }
            }

            DeliveryChip(state = row.presentation.state, hopCount = row.handoffCount)
        }

        Text(
            text = row.presentation.sentence,
            color = BuligColors.InkMuted,
            fontSize = BuligType.BodySmall,
        )

        row.emergencyCode?.let { code ->
            Text(
                text = code,
                color = BuligColors.InkSubtle,
                fontFamily = FontFamily.Monospace,
                fontSize = BuligType.MonoMeta,
            )
        }
    }
}

/** Reassurance, not an error. A resident with no emergencies is the good case. */
@Composable
private fun EmptyState(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(BuligDimens.ScreenPadding * 2),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            color = BuligColors.InkMuted,
            fontSize = BuligType.Body,
        )
        Text(
            text = "That is the good case.",
            color = BuligColors.InkSubtle,
            fontSize = BuligType.BodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
