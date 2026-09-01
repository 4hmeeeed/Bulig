package ph.bulig.app

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ph.bulig.data.presentation.CountField
import ph.bulig.data.presentation.EmergencyTypeCatalog
import ph.bulig.data.presentation.HomeStateFactory
import ph.bulig.data.presentation.HomeUiState
import ph.bulig.data.presentation.MyReportsState
import ph.bulig.data.presentation.MyReportsStateFactory
import ph.bulig.data.presentation.ReportFlowReducer
import ph.bulig.data.presentation.ReportFlowState
import ph.bulig.data.presentation.ReportStep
import ph.bulig.data.presentation.TypeLabel
import ph.bulig.data.repository.ReportRepository
import ph.bulig.data.repository.SaveResult
import ph.bulig.data.store.InMemoryReportStore
import ph.bulig.mesh.crypto.PacketSigner
import ph.bulig.mesh.model.DeviceId

/** Which screen is showing. A four-destination app does not need a nav library. */
enum class Destination { HOME, REPORT_FLOW, MY_REPORTS }

/**
 * The one stateful object in `:app`.
 *
 * Holds no rules of its own — every decision belongs to [ReportFlowReducer] and
 * the state factories in `:data`, all of which are tested without Android. This
 * class exists to own the repository, keep the current screen, and re-derive UI
 * state after each write.
 *
 * ## Two limitations, stated rather than hidden
 *
 * **Storage is in memory.** [InMemoryReportStore] is the test double, used here
 * because Room is not wired yet. Reports therefore do not survive killing the
 * app. That makes this build fine for walking the flow and wrong for anything
 * that needs a report to still exist tomorrow.
 *
 * **Packets are unsigned.** [PacketSigner] is constructed with a null key, so
 * `hmac` stays null and the server would reject these packets as `INVALID_HMAC`.
 * The device key is provisioned at registration, which does not exist yet.
 *
 * Both are deliberate and both are listed in `android/BUILDING.md`.
 */
class BuligViewModel : ViewModel() {

    private val store = InMemoryReportStore()

    private val repository = ReportRepository(
        // TO BE REPLACED: a per-install identifier, persisted and rotated per
        // the pseudonym policy. A fixed string means two phones running this
        // build would claim to be the same device.
        deviceId = DeviceId("dev-local-prototype"),
        store = store,
        signer = PacketSigner(deviceKey = null),
    )

    private val typeLabels: Map<String, TypeLabel> =
        EmergencyTypeCatalog.all.associate {
            it.code to TypeLabel(code = it.code, labelEn = it.labelEn, labelWar = it.labelWar)
        }

    private val _destination = MutableStateFlow(Destination.HOME)
    val destination: StateFlow<Destination> = _destination.asStateFlow()

    private val _home = MutableStateFlow(buildHome())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _myReports = MutableStateFlow(buildMyReports())
    val myReports: StateFlow<MyReportsState> = _myReports.asStateFlow()

    private val _flow = MutableStateFlow(newFlow())
    val flow: StateFlow<ReportFlowState> = _flow.asStateFlow()

    // --- navigation -------------------------------------------------------

    fun startReport() {
        _flow.value = newFlow()
        _destination.value = Destination.REPORT_FLOW
    }

    fun openMyReports() {
        refresh()
        _destination.value = Destination.MY_REPORTS
    }

    fun goHome() {
        refresh()
        _destination.value = Destination.HOME
    }

    // --- the report flow --------------------------------------------------

    fun selectType(code: String) {
        _flow.value = ReportFlowReducer.selectType(_flow.value, code)
    }

    fun adjust(field: CountField, delta: Int) {
        _flow.value = ReportFlowReducer.adjust(_flow.value, field, delta)
    }

    fun setDescription(text: String) {
        _flow.value = ReportFlowReducer.setDescription(_flow.value, text)
    }

    fun setLifeThreatening(value: Boolean) {
        _flow.value = ReportFlowReducer.setLifeThreatening(_flow.value, value)
    }

    fun next() {
        _flow.value = ReportFlowReducer.next(_flow.value, nowMs = System.currentTimeMillis())
    }

    fun back() {
        _flow.value = ReportFlowReducer.back(_flow.value)
    }

    fun jumpTo(step: ReportStep) {
        _flow.value = ReportFlowReducer.jumpTo(_flow.value, step)
    }

    /**
     * Commits the report, then advances the flow.
     *
     * In that order, and never the other way round: the confirmation screen
     * tells the resident their report is saved, so it must not appear until the
     * write has actually happened.
     */
    fun submit() {
        when (val result = repository.save(_flow.value.draft)) {
            is SaveResult.Saved -> {
                _flow.value = ReportFlowReducer.submitted(
                    _flow.value,
                    emergencyCode = result.report.emergencyCode,
                )
                refresh()
            }

            is SaveResult.Invalid -> {
                // The flow's own `canContinue` should make this unreachable. If
                // it happens, staying on the review step is right: silently
                // advancing would claim a save that did not occur.
            }
        }
    }

    // --- derived state ----------------------------------------------------

    private fun refresh() {
        _home.value = buildHome()
        _myReports.value = buildMyReports()
    }

    private fun buildHome(): HomeUiState = HomeStateFactory.build(
        myReports = repository.myReports(),
        carriedForOthers = repository.carriedForOthers(),
        // Hardcoded until connectivity and the mesh service are wired in. Offline
        // is both the honest default and this app's normal case.
        isOnline = false,
        isSyncing = false,
        nearbyPeerCount = 0,
        typeLabels = typeLabels,
    )

    private fun buildMyReports(): MyReportsState = MyReportsStateFactory.build(
        reports = repository.myReports(),
        isOnline = false,
        isSyncing = false,
        typeLabels = typeLabels,
    )

    private fun newFlow() = ReportFlowState(
        types = EmergencyTypeCatalog.all,
        isOnline = false,
    )
}
