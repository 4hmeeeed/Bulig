package ph.bulig.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ph.bulig.app.ble.BuligMeshService
import ph.bulig.app.sync.SyncWorker
import ph.bulig.data.model.LocalReport
import ph.bulig.data.auth.AppMode
import ph.bulig.data.auth.LoginFailure
import ph.bulig.data.auth.SignInResult
import ph.bulig.data.location.LocationPolicy
import ph.bulig.data.location.LocationUiState
import ph.bulig.data.presentation.Assignment
import ph.bulig.data.presentation.AssignmentDetailState
import ph.bulig.data.presentation.AssignmentDetailStateFactory
import ph.bulig.data.presentation.AssignmentListState
import ph.bulig.data.presentation.AssignmentListStateFactory
import ph.bulig.data.presentation.CountField
import ph.bulig.data.presentation.NearbyPeer
import ph.bulig.data.presentation.EmergencyTypeCatalog
import ph.bulig.data.presentation.HomeStateFactory
import ph.bulig.data.presentation.HomeUiState
import ph.bulig.data.presentation.MeshStatusState
import ph.bulig.data.presentation.MeshStatusStateFactory
import ph.bulig.data.presentation.MyReportsState
import ph.bulig.data.presentation.MyReportsStateFactory
import ph.bulig.data.presentation.PermissionUiState
import ph.bulig.data.presentation.ReportDetailState
import ph.bulig.data.presentation.ReportDetailStateFactory
import ph.bulig.data.presentation.ReportFlowReducer
import ph.bulig.data.presentation.ReportFlowState
import ph.bulig.data.presentation.ReportStep
import ph.bulig.data.presentation.TypeLabel
import ph.bulig.data.repository.SaveResult
import ph.bulig.mesh.model.PacketId

/** Which screen is showing. */
enum class Destination {
    PERMISSIONS, HOME, REPORT_FLOW, MY_REPORTS, REPORT_DETAIL, MESH_STATUS,
    LOGIN, ASSIGNMENTS, ASSIGNMENT_DETAIL,
}

/**
 * The one stateful object in `:app`.
 *
 * Holds no rules: every decision belongs to the reducers and factories in
 * `:data`, all of which are tested without Android. This class owns the current
 * screen, and re-derives UI state after each write.
 *
 * Every database call goes through [Dispatchers.IO]. Room refuses main-thread
 * queries and will throw, which is the behaviour we want — a threading mistake
 * should be a loud crash in development rather than a dropped frame on a cheap
 * phone during an emergency.
 */
class BuligViewModel(application: Application) : AndroidViewModel(application) {

    private val bulig = Bulig.get(application)

    private val typeLabels: Map<String, TypeLabel> =
        EmergencyTypeCatalog.all.associate {
            it.code to TypeLabel(code = it.code, labelEn = it.labelEn, labelWar = it.labelWar)
        }

    private val _destination = MutableStateFlow(Destination.HOME)
    val destination: StateFlow<Destination> = _destination.asStateFlow()

    private val _home = MutableStateFlow(HomeStateFactory.build(emptyList(), emptyList(), false, false, 0))
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _myReports = MutableStateFlow(MyReportsStateFactory.build(emptyList(), false, false))
    val myReports: StateFlow<MyReportsState> = _myReports.asStateFlow()

    private val _flow = MutableStateFlow(newFlow())
    val flow: StateFlow<ReportFlowState> = _flow.asStateFlow()

    private val _detail = MutableStateFlow<ReportDetailState?>(null)
    val detail: StateFlow<ReportDetailState?> = _detail.asStateFlow()

    private val _mesh = MutableStateFlow(
        MeshStatusStateFactory.build(emptyList(), emptyList(), 0, 0, isRadioActive = false)
    )
    val mesh: StateFlow<MeshStatusState> = _mesh.asStateFlow()

    private val _permissions = MutableStateFlow<PermissionUiState?>(null)
    val permissions: StateFlow<PermissionUiState?> = _permissions.asStateFlow()

    private val _mode = MutableStateFlow<AppMode>(AppMode.Resident)
    val mode: StateFlow<AppMode> = _mode.asStateFlow()

    private val _loginFailure = MutableStateFlow<LoginFailure?>(null)
    val loginFailure: StateFlow<LoginFailure?> = _loginFailure.asStateFlow()

    private val _isSigningIn = MutableStateFlow(false)
    val isSigningIn: StateFlow<Boolean> = _isSigningIn.asStateFlow()

    private val _location = MutableStateFlow(
        LocationPolicy.build(null, isSearching = false, nowMs = System.currentTimeMillis())
    )
    val location: StateFlow<LocationUiState> = _location.asStateFlow()

    private val _assignments = MutableStateFlow(
        AssignmentListStateFactory.build("", null, emptyList(), System.currentTimeMillis())
    )
    val assignments: StateFlow<AssignmentListState> = _assignments.asStateFlow()

    private val _assignmentDetail = MutableStateFlow<AssignmentDetailState?>(null)
    val assignmentDetail: StateFlow<AssignmentDetailState?> = _assignmentDetail.asStateFlow()

    /**
     * Peers the running relay can currently see.
     *
     * Fed by the Activity's binding to [BuligMeshService]. Empty when nothing is
     * bound, which is honest: a peer list that outlived the service that
     * produced it would be the same class of untruth as a premature delivery
     * tick.
     */
    private val livePeers = MutableStateFlow<List<NearbyPeer>>(emptyList())

    fun onPeersChanged(peers: List<NearbyPeer>) {
        livePeers.value = peers
        refresh()
    }

    init {
        refresh()
        // Opportunistic and non-blocking: a phone that cannot register still
        // files and relays reports, so this never gates anything.
        viewModelScope.launch(Dispatchers.IO) { bulig.registration.ensureRegistered() }
        SyncWorker.schedule(application)
    }

    // --- navigation -------------------------------------------------------

    fun startReport() {
        _flow.value = newFlow()
        _destination.value = Destination.REPORT_FLOW
    }

    fun openMyReports() = go(Destination.MY_REPORTS)
    fun openLogin() {
        _loginFailure.value = null
        _destination.value = Destination.LOGIN
    }
    fun openAssignments() {
        _destination.value = Destination.ASSIGNMENTS
        loadAssignments()
    }

    /**
     * Fetches the queue, and keeps whatever was already there if it cannot.
     *
     * A responder who walks out of signal must not watch their assignments
     * disappear — the last known queue, visibly aged from filing time, is far
     * more useful than an empty screen.
     */
    fun loadAssignments() {
        val session = (_mode.value as? AppMode.Responder)?.session ?: return

        viewModelScope.launch {
            val fetched: List<Assignment>? = try {
                withContext(Dispatchers.IO) { bulig.assignments.mine(session.token) }
            } catch (e: Exception) {
                null
            }

            _assignments.value = AssignmentListStateFactory.build(
                responderName = session.name,
                zone = session.badgeNo,
                assignments = fetched ?: _assignments.value.rows.map { it.assignment },
                nowMs = System.currentTimeMillis(),
                typeLabels = typeLabels,
            )
        }
    }
    fun openAssignment(emergencyCode: String) {
        val found = _assignments.value.rows
            .firstOrNull { it.emergencyCode == emergencyCode }
            ?.assignment
            ?: return

        _assignmentDetail.value = AssignmentDetailStateFactory.build(
            assignment = found,
            nowMs = System.currentTimeMillis(),
            typeLabels = typeLabels,
        )
        _destination.value = Destination.ASSIGNMENT_DETAIL
    }

    /**
     * Moves an assignment to its next status, **locally first**.
     *
     * The change is shown immediately and marked unsynced, because a responder
     * standing in floodwater tapping ON SITE must see it take effect whether or
     * not the barangay server can be reached. The action bar then says
     * "not yet uploaded" — the same honesty rule a resident's report obeys.
     *
     * TO BE WIRED: pushing the change to
     * PATCH /api/v1/assignments/{id}/status. Until then the status lives on this
     * phone only, which the pill states rather than hides.
     */
    fun advanceAssignment() {
        val current = _assignmentDetail.value?.assignment ?: return

        val next = when (current.status) {
            ph.bulig.data.presentation.ResponderStatus.ASSIGNED ->
                ph.bulig.data.presentation.ResponderStatus.ACCEPTED
            ph.bulig.data.presentation.ResponderStatus.ACCEPTED ->
                ph.bulig.data.presentation.ResponderStatus.EN_ROUTE
            ph.bulig.data.presentation.ResponderStatus.EN_ROUTE ->
                ph.bulig.data.presentation.ResponderStatus.ON_SITE
            ph.bulig.data.presentation.ResponderStatus.ON_SITE ->
                ph.bulig.data.presentation.ResponderStatus.RESOLVED
            // Closed states have no next step, and the action bar disables the
            // button anyway.
            else -> return
        }

        _assignmentDetail.value = AssignmentDetailStateFactory.build(
            assignment = current.copy(status = next, statusSynced = false),
            nowMs = System.currentTimeMillis(),
            typeLabels = typeLabels,
        )
    }

    fun openMesh() = go(Destination.MESH_STATUS)
    fun goHome() = go(Destination.HOME)

    fun openReport(packetId: PacketId) {
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { bulig.store.get(packetId) } ?: return@launch

            _detail.value = ReportDetailStateFactory.build(report, typeLabels)
            _destination.value = Destination.REPORT_DETAIL
        }
    }

    fun showPermissions(state: PermissionUiState) {
        _permissions.value = state
        _destination.value = Destination.PERMISSIONS
    }

    fun onPermissionsSettled(state: PermissionUiState) {
        _permissions.value = state

        // A phone with any radio capability at all is worth starting the relay
        // on; one with none would only burn battery holding a foreground
        // notification it cannot act behind.
        if (state.capability != ph.bulig.data.presentation.MeshCapability.NONE) {
            BuligMeshService.start(getApplication())
        }

        goHome()
    }

    private fun go(destination: Destination) {
        refresh()
        _destination.value = destination
    }

    // --- the report flow --------------------------------------------------

    fun selectType(code: String) { _flow.value = ReportFlowReducer.selectType(_flow.value, code) }
    fun adjust(field: CountField, delta: Int) { _flow.value = ReportFlowReducer.adjust(_flow.value, field, delta) }
    fun setDescription(text: String) { _flow.value = ReportFlowReducer.setDescription(_flow.value, text) }
    fun setLifeThreatening(value: Boolean) { _flow.value = ReportFlowReducer.setLifeThreatening(_flow.value, value) }
    fun next() {
        _flow.value = ReportFlowReducer.next(_flow.value, System.currentTimeMillis())

        // The fix is asked for on arrival at the step rather than at launch: a
        // resident who never files a report should never have the GPS radio
        // turned on for them.
        if (_flow.value.step == ReportStep.LOCATION) requestLocation()
    }
    fun back() { _flow.value = ReportFlowReducer.back(_flow.value) }
    fun jumpTo(step: ReportStep) { _flow.value = ReportFlowReducer.jumpTo(_flow.value, step) }

    /**
     * Commits the report, then advances the flow.
     *
     * In that order, and never the other way round: the confirmation screen
     * tells the resident their report is saved, so it must not appear until the
     * write has actually happened.
     */
    fun submit() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { bulig.repository.save(_flow.value.draft) }

            when (result) {
                is SaveResult.Saved -> {
                    _flow.value = ReportFlowReducer.submitted(
                        _flow.value, emergencyCode = result.report.emergencyCode,
                    )
                    refresh()

                    // Try immediately rather than waiting for the periodic run.
                    // Constrained on a network, so it costs nothing offline.
                    SyncWorker.enqueueNow(getApplication())
                }

                is SaveResult.Invalid -> {
                    // The flow's own canContinue should make this unreachable.
                    // Staying on review is right: advancing would claim a save
                    // that did not occur.
                }
            }
        }
    }

    // --- derived state ----------------------------------------------------

    fun refresh() {
        viewModelScope.launch {
            val mine: List<LocalReport>
            val others: List<LocalReport>
            val online: Boolean

            withContext(Dispatchers.IO) {
                mine = bulig.store.mine()
                others = bulig.store.carriedForOthers()
                online = bulig.hasValidatedInternet()
            }

            _home.value = HomeStateFactory.build(
                myReports = mine,
                carriedForOthers = others,
                isOnline = online,
                isSyncing = false,
                nearbyPeerCount = livePeers.value.size,
                typeLabels = typeLabels,
            )

            _myReports.value = MyReportsStateFactory.build(
                reports = mine, isOnline = online, isSyncing = false, typeLabels = typeLabels,
            )

            _mesh.value = MeshStatusStateFactory.build(
                carriedForOthers = others,
                // TO BE WIRED: the live peer list comes from BuligMeshService,
                // which needs a binder this build does not have. Showing an
                // empty list is honest; inventing peers would not be.
                peers = livePeers.value,
                passedOnToday = others.size,
                deliveredBecauseOfYou = others.count { it.synced },
                isRadioActive = ph.bulig.app.ble.MeshRadioStatus.canAdvertise.value,
            )
        }
    }

    // --- responder sign-in --------------------------------------------------

    /**
     * Signs a responder in, and never blocks the resident experience on it.
     *
     * A failure leaves [mode] where it was — which for anybody who has not
     * signed in is [AppMode.Resident], with the report flow fully intact.
     */
    fun signIn(email: String, password: String) {
        if (_isSigningIn.value) return

        _isSigningIn.value = true
        _loginFailure.value = null

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { bulig.sessions.signIn(email, password) }

            _isSigningIn.value = false

            when (result) {
                is SignInResult.Success -> {
                    _mode.value = result.mode
                    _destination.value = when (result.mode) {
                        is AppMode.Responder -> Destination.ASSIGNMENTS
                        else -> Destination.HOME
                    }
                }

                is SignInResult.Failed -> _loginFailure.value = result.failure
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { bulig.sessions.signOut() }
            _mode.value = AppMode.Resident
            goHome()
        }
    }

    // --- location -----------------------------------------------------------

    /**
     * Asks for a fix, showing the cached one immediately if there is a usable
     * one.
     *
     * Never blocks the flow: [LocationUiState.canContinue] is always true, and a
     * resident who moves on before a fix arrives simply sends a report without
     * one.
     */
    fun requestLocation() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()

            val cached = withContext(Dispatchers.IO) { bulig.location.lastKnownFix() }
            if (cached != null && !LocationPolicy.isStale(cached, now)) {
                _location.value = LocationPolicy.build(cached, isSearching = true, nowMs = now)
                applyFix(cached)
            } else {
                _location.value = LocationPolicy.build(null, isSearching = true, nowMs = now)
            }

            val fresh = withContext(Dispatchers.IO) { bulig.location.currentFix() }
            val settledAt = System.currentTimeMillis()

            val held = _location.value.fix
            val chosen = when {
                fresh == null -> held
                LocationPolicy.shouldReplace(held, fresh, settledAt) -> fresh
                else -> held
            }

            _location.value = LocationPolicy.build(chosen, isSearching = false, nowMs = settledAt)
            chosen?.let { applyFix(it) }
        }
    }

    private fun applyFix(fix: ph.bulig.data.location.LocationFix) {
        _flow.value = ReportFlowReducer.setLocation(
            state = _flow.value,
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracyM = fix.accuracyM,
            provider = fix.provider,
            capturedAtMs = fix.capturedAtMs,
        )
    }

    private fun newFlow() = ReportFlowState(types = EmergencyTypeCatalog.all, isOnline = false)
}
