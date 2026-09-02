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
import ph.bulig.data.presentation.CountField
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
enum class Destination { PERMISSIONS, HOME, REPORT_FLOW, MY_REPORTS, REPORT_DETAIL, MESH_STATUS }

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
    fun next() { _flow.value = ReportFlowReducer.next(_flow.value, System.currentTimeMillis()) }
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
                nearbyPeerCount = 0,
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
                peers = emptyList(),
                passedOnToday = others.size,
                deliveredBecauseOfYou = others.count { it.synced },
                isRadioActive = ph.bulig.app.ble.MeshRadioStatus.canAdvertise.value,
            )
        }
    }

    private fun newFlow() = ReportFlowState(types = EmergencyTypeCatalog.all, isOnline = false)
}
