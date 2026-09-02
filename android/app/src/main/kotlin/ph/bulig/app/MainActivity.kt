package ph.bulig.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import android.os.IBinder
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collectLatest
import ph.bulig.app.ble.BuligMeshService
import ph.bulig.app.screens.AssignmentDetailScreen
import ph.bulig.app.screens.AssignmentListScreen
import ph.bulig.app.screens.HomeScreen
import ph.bulig.app.screens.LoginScreen
import ph.bulig.app.screens.MeshStatusScreen
import ph.bulig.app.screens.MyReportsScreen
import ph.bulig.app.screens.PermissionScreen
import ph.bulig.app.screens.ReportDetailScreen
import ph.bulig.app.screens.ReportFlowScreen
import ph.bulig.app.theme.BuligColors
import ph.bulig.app.theme.BuligTheme
import ph.bulig.data.auth.AppMode
import ph.bulig.data.presentation.MeshPermission
import ph.bulig.data.presentation.PermissionStateFactory
import ph.bulig.data.presentation.PermissionUiState
import ph.bulig.data.presentation.ReportStep

/**
 * The whole resident app.
 *
 * No navigation library: a handful of destinations with one back edge each do
 * not justify a back stack, and a nav graph would be another place for the
 * report flow's state to be lost on a configuration change.
 *
 * **A resident never signs in.** Sign-in exists only for responders, is reached
 * only by a deliberate tap, and is never in anybody's way — requiring an account
 * to report an emergency would put a network call in front of the one action
 * that must work with no network at all.
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

    // Bound only while the app is on screen. The relay keeps running either
    // way — this connection exists so the Mesh Status screen can show what it
    // is actually doing, and a peer list that outlived the service that
    // produced it would be worse than none.
    var binder by remember { mutableStateOf<BuligMeshService.LocalBinder?>(null) }

    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service as? BuligMeshService.LocalBinder
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
            }
        }

        val didBind = try {
            context.bindService(
                Intent(context, BuligMeshService::class.java),
                connection,
                // Never BIND_AUTO_CREATE: the service's lifetime belongs to the
                // permission flow, not to whether a screen happens to be open.
                0,
            )
        } catch (e: Exception) {
            false
        }

        onDispose {
            if (didBind) {
                try {
                    context.unbindService(connection)
                } catch (e: IllegalArgumentException) {
                    // Already unbound because the service died. Nothing to undo.
                }
            }
            binder = null
        }
    }

    // Follows the service's own flow rather than polling it. Clearing the list
    // when nothing is bound is the honest default.
    LaunchedEffect(binder) {
        val live = binder
        if (live == null) {
            viewModel.onPeersChanged(emptyList())
        } else {
            live.peers.collectLatest { viewModel.onPeersChanged(it) }
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

            val mode by viewModel.mode.collectAsStateWithLifecycle()

            HomeScreen(
                state = state,
                onReportEmergency = viewModel::startReport,
                // A signed-in responder's mesh affordance takes them to their
                // queue instead; everyone else sees the mesh status they own.
                onOpenMesh = {
                    if (mode is AppMode.Responder) viewModel.openAssignments() else viewModel.openMesh()
                },
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

        Destination.LOGIN -> {
            val failure by viewModel.loginFailure.collectAsStateWithLifecycle()
            val working by viewModel.isSigningIn.collectAsStateWithLifecycle()

            LoginScreen(
                onSignIn = viewModel::signIn,
                onBack = viewModel::goHome,
                isWorking = working,
                failure = failure,
            )
        }

        Destination.ASSIGNMENTS -> {
            val mode by viewModel.mode.collectAsStateWithLifecycle()
            val responder = mode as? AppMode.Responder

            if (responder == null) {
                // Signed out from another screen, or a token expired. Home is
                // the safe place: it is the one that still lets somebody report.
                LaunchedEffect(Unit) { viewModel.goHome() }
            } else {
                val queue by viewModel.assignments.collectAsStateWithLifecycle()

                AssignmentListScreen(
                    state = queue,
                    onOpen = { viewModel.openAssignment(it.emergencyCode) },
                    onBack = viewModel::goHome,
                )
            }
        }

        Destination.ASSIGNMENT_DETAIL -> {
            val detail by viewModel.assignmentDetail.collectAsStateWithLifecycle()

            detail?.let {
                AssignmentDetailScreen(
                    state = it,
                    onPrimary = viewModel::advanceAssignment,
                    onSecondary = viewModel::openAssignments,
                    onEscalate = viewModel::openAssignments,
                    onBack = viewModel::openAssignments,
                )
            } ?: LaunchedEffect(Unit) { viewModel.openAssignments() }
        }
    }
}
