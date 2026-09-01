package ph.bulig.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ph.bulig.app.screens.HomeScreen
import ph.bulig.app.screens.MyReportsScreen
import ph.bulig.app.screens.ReportFlowScreen
import ph.bulig.app.theme.BuligColors
import ph.bulig.app.theme.BuligTheme
import ph.bulig.data.presentation.ReportStep

/**
 * The whole app, in three destinations.
 *
 * No navigation library: three screens with one back edge each do not justify a
 * back stack, and a nav graph would be another place for the report flow's state
 * to be lost on a configuration change.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            BuligTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars),
                    color = BuligColors.Canvas,
                ) {
                    BuligApp()
                }
            }
        }
    }
}

@Composable
private fun BuligApp(viewModel: BuligViewModel = viewModel()) {
    val destination by viewModel.destination.collectAsStateWithLifecycle()

    // The system back button follows the same edges as the on-screen ones, so a
    // resident cannot end up somewhere the UI has no way out of.
    BackHandler(enabled = destination != Destination.HOME) {
        if (destination == Destination.REPORT_FLOW) viewModel.back() else viewModel.goHome()
    }

    when (destination) {
        Destination.HOME -> {
            val state by viewModel.home.collectAsStateWithLifecycle()

            HomeScreen(
                state = state,
                onReportEmergency = viewModel::startReport,
                onOpenMesh = viewModel::openMyReports,
                onOpenReport = { viewModel.openMyReports() },
            )
        }

        Destination.REPORT_FLOW -> {
            val state by viewModel.flow.collectAsStateWithLifecycle()

            ReportFlowScreen(
                state = state,
                onSelectType = viewModel::selectType,
                onAdjust = viewModel::adjust,
                onDescription = viewModel::setDescription,
                onLifeThreatening = viewModel::setLifeThreatening,
                onNext = viewModel::next,
                // Backing out of the first step leaves the flow entirely; the
                // reducer refuses to go back from TYPE, so this decides it.
                onBack = {
                    if (state.step == ReportStep.TYPE) viewModel.goHome() else viewModel.back()
                },
                onJumpTo = viewModel::jumpTo,
                onSubmit = viewModel::submit,
                onDone = viewModel::openMyReports,
            )
        }

        Destination.MY_REPORTS -> {
            val state by viewModel.myReports.collectAsStateWithLifecycle()

            MyReportsScreen(
                state = state,
                onBack = viewModel::goHome,
                onOpenReport = { /* report detail is a later slice */ },
            )
        }
    }
}
