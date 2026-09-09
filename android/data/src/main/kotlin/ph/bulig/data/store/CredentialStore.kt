package ph.bulig.data.store

import ph.bulig.data.sync.DeviceCredentials

/**
 * Where this device's identity and signing key live.
 *
 * An interface rather than a concrete class because the real implementation is
 * Android's EncryptedSharedPreferences, which cannot be compiled or tested off a
 * phone. Everything that *decides* anything about credentials sits above this
 * line and is tested against [InMemoryCredentialStore].
 *
 * Implementations must treat the key as a secret: never logged, never included
 * in a crash report, never written to shared storage.
 */
interface CredentialStore {
    fun load(): DeviceCredentials?
    fun save(credentials: DeviceCredentials)

    /** Called when the server revokes this device. */
    fun clear()
}

/** For tests, and for a build deliberately running without persistence. */
class InMemoryCredentialStore(
    private var current: DeviceCredentials? = null,
) : CredentialStore {
    override fun load(): DeviceCredentials? = current
    override fun save(credentials: DeviceCredentials) { current = credentials }
    override fun clear() { current = null }
}

/**
 * Where this device's own identifier comes from.
 *
 * Minted once per install and kept, because it is the identity the server binds
 * a signing key to. A device that regenerated it on every launch would need to
 * re-register constantly, and every packet it had already relayed would appear
 * to come from a stranger.
 */
interface DeviceIdentityStore {
    fun loadDeviceId(): String?
    fun saveDeviceId(id: String)
}

class InMemoryDeviceIdentityStore(private var id: String? = null) : DeviceIdentityStore {
    override fun loadDeviceId(): String? = id
    override fun saveDeviceId(id: String) { this.id = id }
}
