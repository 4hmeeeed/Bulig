package ph.bulig.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ph.bulig.app.screens.HomeScreen
import ph.bulig.app.screens.MeshStatusScreen
import ph.bulig.app.screens.MyReportsScreen
import ph.bulig.app.screens.PermissionScreen
import ph.bulig.app.screens.ReportDetailScreen
import ph.bulig.app.screens.ReportFlowScreen
import ph.bulig.app.theme.BuligColors
import ph.bulig.app.theme.BuligTheme
import ph.bulig.data.presentation.MeshPermission
import ph.bulig.data.presentation.PermissionStateFactory
import ph.bulig.data.presentation.PermissionUiState
import ph.bulig.data.presentation.ReportStep

/**
 * The whole resident app.
 *
 * No navigation library: six destinations with one back edge each do not justify
 * a back stack, and a nav graph would be another place for the report flow's
 * state to be lost on a configuration change.
 *
 * The responder screens exist and are tested but are not routed here. This build
 * has no sign-in, so every install is a resident — showing a responder queue to
 * somebody with no assignments would be worse than not showing it at all.
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

/** Maps our own permission vocabulary onto Android's, per platform. */
private fun androidPermission(permission: MeshPermission): String? = when (permission) {
    MeshPermission.FIND_NEARBY_PHONES ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            // Below API 31 scanning genuinely required location permission,
            // which is why the legacy path asks for something the app has no
            // interest in knowing.
            Manifest.permission.ACCESS_COARSE_LOCATION
        }

    MeshPermission.BE_FOUND_BY_OTHERS ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_ADVERTISE
        } else {
            null // Implicit in the legacy BLUETOOTH_ADMIN manifest permission.
        }

    MeshPermission.EXCHANGE_REPORTS ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            null
        }

    MeshPermission.SHOW_RELAY_NOTICE ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }
}

/** A permission with no runtime equivalent on this release counts as held. */
private fun android.content.Context.holds(permission: MeshPermission): Boolean {
    val name = androidPermission(permission) ?: return true

    return ContextCompat.checkSelfPermission(this, name) == PackageManager.PERMISSION_GRANTED
}

private fun android.content.Context.permissionState(): PermissionUiState =
    PermissionStateFactory.build(
        granted = PermissionStateFactory.required(Build.VERSION.SDK_INT)
            .filter { holds(it) }
            .toSet()
    )

@Composable
private fun BuligApp(viewModel: BuligViewModel = viewModel()) {
    val destination by viewModel.destination.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // The result map is ignored deliberately: what the system actually holds
        // is authoritative, and re-reading it avoids trusting a callback that
        // reports on names rather than on capability.
        viewModel.onPermissionsSettled(context.permissionState())
    }

    // Asked once on first composition. If everything is already held the screen
    // never appears — a resident who granted last week should not be asked again.
    LaunchedEffect(Unit) {
        val state = context.permissionState()
        if (state.isFullyGranted) {
            viewModel.onPermissionsSettled(state)
        } else {
            viewModel.showPermissions(state)
        }
    }

    BackHandler(enabled = destination != Destination.HOME) {
        if (destination == Destination.REPORT_FLOW) viewModel.back() else viewModel.goHome()
    }

    when (destination) {
        Destination.PERMISSIONS -> {
            val state by viewModel.permissions.collectAsStateWithLifecycle()

            state?.let {
                PermissionScreen(
                    state = it,
                    onRequest = {
                        if (it.needsSettings) {
                            // Android stops showing the dialog after two
                            // refusals, so the only honest next step is to send
                            // the resident where the choice still exists.
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                )
                            )
                        } else {
                            launcher.launch(
                                PermissionStateFactory.required(Build.VERSION.SDK_INT)
                                    .mapNotNull(::androidPermission)
                                    .toTypedArray()
                            )
                        }
                    },
                    // Never a gate: a phone with no permissions can still save a
                    // report, and blocking that would be the worse failure.
                    onSkip = { viewModel.onPermissionsSettled(it) },
                )
            }
        }

        Destination.HOME -> {
            val state by viewModel.home.collectAsStateWithLifecycle()

            HomeScreen(
                state = state,
                onReportEmergency = viewModel::startReport,
                onOpenMesh = viewModel::openMesh,
                onOpenReport = { viewModel.openReport(it.packetId) },
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
                // The reducer refuses to go back from TYPE, so leaving the flow
                // entirely is decided here.
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
                onOpenReport = { viewModel.openReport(it.packetId) },
            )
        }

        Destination.REPORT_DETAIL -> {
            val state by viewModel.detail.collectAsStateWithLifecycle()

            state?.let {
                ReportDetailScreen(
                    state = it,
                    onBack = viewModel::openMyReports,
                    onCheckForUpdates = viewModel::refresh,
                )
            }
        }

        Destination.MESH_STATUS -> {
            val state by viewModel.mesh.collectAsStateWithLifecycle()

            MeshStatusScreen(state = state, onBack = viewModel::goHome)
        }
    }
}
