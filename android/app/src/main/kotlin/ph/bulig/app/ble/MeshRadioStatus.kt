package ph.bulig.app.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the radio is actually able to do on this handset.
 *
 * Process-scoped rather than injected, because the service and the Mesh Status
 * screen have no shared lifetime: the service outlives every screen, and the
 * screen must be able to answer the question the moment it opens.
 *
 * The one fact it carries is the one a resident would otherwise never learn.
 * Some chipsets cannot advertise at all. Such a phone still relays — it connects
 * outward and hands packets on — but it can never be *found*, so it will never
 * receive one. Reporting the app as simply "working" on that device would be a
 * delivery promise it cannot keep, which is the same honesty rule the delivery
 * states follow.
 */
object MeshRadioStatus {

    private val _canAdvertise = MutableStateFlow(true)

    /** False once the platform has told us advertising is unsupported or refused. */
    val canAdvertise: StateFlow<Boolean> = _canAdvertise.asStateFlow()

    fun reportAdvertisingUnsupported() {
        _canAdvertise.value = false
    }

    /** Advertising started successfully — recovers the flag after a radio restart. */
    fun reportAdvertisingActive() {
        _canAdvertise.value = true
    }
}
