package ph.bulig.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import ph.bulig.app.store.ReportDatabase
import ph.bulig.app.store.RoomReportStore
import ph.bulig.app.location.FusedLocationSource
import ph.bulig.app.store.SecureStorage
import ph.bulig.data.auth.AssignmentActions
import ph.bulig.data.auth.AssignmentApi
import ph.bulig.data.auth.AuthApi
import ph.bulig.data.auth.InMemorySessionStore
import ph.bulig.data.auth.SessionManager
import ph.bulig.data.delivery.DeliveryStateMachine
import ph.bulig.data.registration.RegistrationManager
import ph.bulig.data.repository.ReportRepository
import ph.bulig.data.store.ReportStore
import ph.bulig.data.sync.DeviceRegistrar
import ph.bulig.data.sync.HttpSyncApi
import ph.bulig.data.sync.SyncConfig
import ph.bulig.data.sync.SyncCoordinator
import ph.bulig.mesh.crypto.PacketSigner
import ph.bulig.mesh.model.DeviceId

/**
 * The object graph, assembled by hand.
 *
 * No dependency-injection framework: this app has one graph, built once, and a
 * DI library would add a compile step and a layer of indirection to save writing
 * the fifteen lines below. A capstone panel can read this file and see the whole
 * system's wiring; they could not read a generated component.
 *
 * Everything here is lazy, because [SecureStorage] touches the Keystore and
 * [ReportDatabase] opens an encrypted file — neither belongs on the path of a
 * cold start that might just be showing the Home screen.
 */
class Bulig(private val context: Context) {

    private val secureStorage: SecureStorage by lazy { SecureStorage(context) }

    val store: ReportStore by lazy {
        RoomReportStore(ReportDatabase.open(context, secureStorage.databasePassphrase()).reports())
    }

    val registration: RegistrationManager by lazy {
        RegistrationManager(
            registrar = DeviceRegistrar(syncConfig()),
            credentials = secureStorage,
            identity = secureStorage,
            deviceModel = android.os.Build.MODEL,
            androidVersion = android.os.Build.VERSION.RELEASE,
        )
    }

    /**
     * The write path.
     *
     * Note what it is given: a store and a signer, and no network client of any
     * kind. That is the architecture's central property made structural — this
     * object physically cannot reach the network, so it cannot accidentally
     * become dependent on it.
     *
     * The signer holds whatever key registration has managed to obtain. Null is
     * a normal state: an unregistered phone signs nothing, and the server
     * records `hmac_valid` as null rather than refusing the report.
     */
    val repository: ReportRepository by lazy {
        ReportRepository(
            deviceId = DeviceId(registration.deviceId()),
            store = store,
            signer = PacketSigner(registration.signingKey()),
        )
    }

    val deliveryStateMachine: DeliveryStateMachine by lazy { DeliveryStateMachine(store) }

    val location: FusedLocationSource by lazy { FusedLocationSource(context) }

    /**
     * Responder sign-in.
     *
     * The session store is in memory on purpose. A responder's token is the
     * most sensitive thing this app holds after the signing key, and a phone
     * handed to somebody else between shifts should not still be signed in.
     * Signing in once per session is a small cost; residents never do it at all.
     */
    val sessions: SessionManager by lazy {
        SessionManager(api = AuthApi(syncConfig()), store = InMemorySessionStore())
    }

    val assignments: AssignmentApi by lazy { AssignmentApi(syncConfig()) }

    val assignmentActions: AssignmentActions by lazy { AssignmentActions(syncConfig()) }

    /**
     * Rebuilt per sync rather than held, because the device token changes when
     * registration succeeds or is rejected, and a cached client would carry a
     * dead credential until the process restarted.
     */
    fun syncCoordinator(): SyncCoordinator = SyncCoordinator(
        store = store,
        api = HttpSyncApi(syncConfig().copy(deviceToken = registration.deviceToken())),
        connectivity = { hasValidatedInternet() },
        stateMachine = deliveryStateMachine,
    )

    /**
     * `NET_CAPABILITY_VALIDATED`, not merely connected.
     *
     * A phone attached to a captive portal is "connected" and can reach nothing.
     * Treating that as online would burn a sync attempt and, worse, make this
     * device advertise itself to peers as a route out that it is not.
     */
    fun hasValidatedInternet(): Boolean {
        val manager = ContextCompat.getSystemService(context, ConnectivityManager::class.java)
            ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun syncConfig() = SyncConfig(baseUrl = BASE_URL)

    companion object {
        /**
         * TO BE CONFIGURED before the pilot: the barangay's own server.
         *
         * 10.0.2.2 is the host machine as seen from the Android emulator, which
         * is right for development and wrong for every phone. A real deployment
         * needs an HTTPS address; cleartext to this one is permitted only by the
         * debug network-security config.
         */
        const val BASE_URL = "http://10.0.2.2:8000"

        @Volatile
        private var instance: Bulig? = null

        fun get(context: Context): Bulig =
            instance ?: synchronized(this) {
                instance ?: Bulig(context.applicationContext).also { instance = it }
            }
    }
}
