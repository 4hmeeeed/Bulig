package ph.bulig.data.registration

import ph.bulig.data.store.CredentialStore
import ph.bulig.data.store.DeviceIdentityStore
import ph.bulig.data.sync.DeviceCredentials
import ph.bulig.data.sync.DeviceRegistrar
import ph.bulig.data.sync.RegisterRequestDto
import ph.bulig.data.sync.SyncException
import ph.bulig.data.sync.SyncFailure

/** What one registration attempt concluded. */
sealed interface RegistrationOutcome {
    /** Already had credentials; nothing was asked of the server. */
    data class AlreadyRegistered(val credentials: DeviceCredentials) : RegistrationOutcome

    data class Registered(val credentials: DeviceCredentials) : RegistrationOutcome

    /** No connection, or the server was broken. Try again at the next opportunity. */
    data class Deferred(val reason: String) : RegistrationOutcome

    /** The server refused permanently. Stop asking. */
    data class Refused(val reason: String) : RegistrationOutcome
}

/**
 * Decides *when* to register, which is the part with rules in it.
 *
 * [DeviceRegistrar] performs one HTTP call. This decides whether to make it at
 * all, mints and persists the device identity, and stores the result. Separated
 * so the policy below is tested without a network or a keystore.
 *
 * ## The rule that matters
 *
 * **Registration never blocks reporting.** A phone that has never reached the
 * server can still create and relay packets; the server records `hmac_valid` as
 * null for them. Requiring registration first would reintroduce the internet
 * dependency this whole architecture exists to remove, and would mean a resident
 * who installed the app during the storm could file nothing at all.
 *
 * So this is called opportunistically — whenever connectivity happens to exist —
 * and every failure it can return is one the caller ignores and carries on from.
 *
 * @see docs/05-api-contract.md 5.1
 */
class RegistrationManager(
    private val registrar: DeviceRegistrar,
    private val credentials: CredentialStore,
    private val identity: DeviceIdentityStore,
    private val newUuid: () -> String = { java.util.UUID.randomUUID().toString() },
    private val deviceModel: String? = null,
    private val androidVersion: String? = null,
    private val supportsAdvertising: Boolean = true,
) {

    /**
     * The device's own identifier, minted on first call and kept afterwards.
     *
     * Public because packets need it long before registration succeeds — a
     * report filed on a phone that has never had signal still has to say which
     * device it came from.
     */
    fun deviceId(): String =
        identity.loadDeviceId() ?: newUuid().also { identity.saveDeviceId(it) }

    fun isRegistered(): Boolean = credentials.load() != null

    /** Null until registration succeeds, which is a normal state. */
    fun signingKey(): ByteArray? = credentials.load()?.hmacKey

    fun deviceToken(): String? = credentials.load()?.token

    /**
     * Registers if it has not already, and never throws.
     *
     * The caller is a background worker or a launch path, and neither should
     * crash because a barangay's server is down.
     */
    fun ensureRegistered(): RegistrationOutcome {
        credentials.load()?.let { return RegistrationOutcome.AlreadyRegistered(it) }

        return try {
            val fresh = registrar.register(
                RegisterRequestDto(
                    deviceId = deviceId(),
                    model = deviceModel,
                    androidVersion = androidVersion,
                    supportsAdvertising = supportsAdvertising,
                )
            )

            credentials.save(fresh)
            RegistrationOutcome.Registered(fresh)
        } catch (e: SyncException) {
            outcomeFor(e.failure)
        } catch (e: Exception) {
            // Anything unexpected is treated as retryable rather than fatal:
            // giving up permanently on an unknown error would leave a device
            // unable to sign anything for the rest of the pilot.
            RegistrationOutcome.Deferred(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    /**
     * Forgets this device's credentials.
     *
     * Called when the server answers 401 — the token is dead, and holding a dead
     * token would make every later sync fail the same way. The device id is
     * deliberately kept: it is the identity the packets already in the mesh were
     * signed under, and changing it would orphan them.
     */
    fun onCredentialsRejected() = credentials.clear()

    private fun outcomeFor(failure: SyncFailure): RegistrationOutcome = when {
        failure.isTransient -> RegistrationOutcome.Deferred(failure.toString())

        // A revoked device is an operator's decision. Retrying cannot undo it.
        failure is SyncFailure.Rejected -> RegistrationOutcome.Refused(failure.detail)

        failure is SyncFailure.Unauthorized ->
            RegistrationOutcome.Refused("server rejected the registration request")

        // A malformed answer may be a proxy or a captive portal rather than the
        // real server, so it is worth trying again later.
        else -> RegistrationOutcome.Deferred(failure.toString())
    }
}
