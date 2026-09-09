package ph.bulig.app.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import ph.bulig.data.store.CredentialStore
import ph.bulig.data.store.DeviceIdentityStore
import ph.bulig.data.sync.DeviceCredentials

/**
 * Every secret this app holds, kept in preferences encrypted by a Keystore key.
 *
 * The Keystore key never leaves the secure hardware where the device has it, so
 * an attacker with the raw preference file has ciphertext and nothing else.
 * That is the whole reason this class exists rather than plain
 * `SharedPreferences`: it holds the database passphrase and the device's signing
 * key, and either one in cleartext would make the encryption above it decorative.
 *
 * Two secrets, two different jobs:
 *
 * - The **database passphrase** is generated once on this device and never
 *   leaves it. Nobody else needs it, so it is never transmitted.
 * - The **signing key** comes from the server at registration, is returned
 *   exactly once, and is what lets the server tell a genuine report from a
 *   forged one.
 */
class SecureStorage(context: Context) : CredentialStore, DeviceIdentityStore {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context.applicationContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // --- database passphrase ----------------------------------------------

    /**
     * The passphrase for the SQLCipher database, generated on first use.
     *
     * `SecureRandom` with no seed: seeding it from anything predictable — a
     * timestamp, an install id — would make every install's database openable by
     * anyone who could guess the seed.
     */
    fun databasePassphrase(): ByteArray {
        prefs.getString(KEY_DB_PASSPHRASE, null)?.let { return decodeHex(it) }

        val fresh = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_DB_PASSPHRASE, encodeHex(fresh)).apply()

        return fresh
    }

    // --- device credentials -------------------------------------------------

    override fun load(): DeviceCredentials? {
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val keyHex = prefs.getString(KEY_HMAC, null) ?: return null

        return DeviceCredentials(
            deviceId = deviceId,
            token = token,
            hmacKey = decodeHex(keyHex),
            ttlInitial = prefs.getInt(KEY_TTL, DEFAULT_TTL),
        )
    }

    override fun save(credentials: DeviceCredentials) {
        prefs.edit()
            .putString(KEY_DEVICE_ID, credentials.deviceId)
            .putString(KEY_TOKEN, credentials.token)
            .putString(KEY_HMAC, encodeHex(credentials.hmacKey))
            .putInt(KEY_TTL, credentials.ttlInitial)
            .apply()
    }

    /**
     * Clears the token and key but **keeps the device id**.
     *
     * The id is the identity under which packets already travelling through the
     * mesh were signed. Changing it would orphan them.
     */
    override fun clear() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_HMAC)
            .remove(KEY_TTL)
            .apply()
    }

    // --- device identity ----------------------------------------------------

    override fun loadDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)

    override fun saveDeviceId(id: String) {
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
    }

    private companion object {
        const val FILE_NAME = "bulig-secure"
        const val KEY_DB_PASSPHRASE = "db_passphrase"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_TOKEN = "device_token"
        const val KEY_HMAC = "hmac_key"
        const val KEY_TTL = "ttl_initial"

        /** 32 bytes, matching the strength of the key the server issues. */
        const val PASSPHRASE_BYTES = 32
        const val DEFAULT_TTL = 10

        fun encodeHex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }

        fun decodeHex(text: String): ByteArray =
            ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
