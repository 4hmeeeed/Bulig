package ph.bulig.data.presentation

/** The permissions the relay actually needs, named as the app talks about them. */
enum class MeshPermission {
    /** BLUETOOTH_SCAN on API 31+, ACCESS_COARSE_LOCATION below it. */
    FIND_NEARBY_PHONES,

    /** BLUETOOTH_ADVERTISE. Absent means this phone can never be found. */
    BE_FOUND_BY_OTHERS,

    /** BLUETOOTH_CONNECT. Absent means nothing can be exchanged at all. */
    EXCHANGE_REPORTS,

    /** POST_NOTIFICATIONS on API 33+. The foreground service needs a visible notice. */
    SHOW_RELAY_NOTICE,
}

/** How much of the mesh a phone can take part in with what it has been granted. */
enum class MeshCapability {
    /** Can be found, can reach out, can carry. */
    FULL,

    /** Can find and connect, but never be found — so it can forward, never receive. */
    OUTGOING_ONLY,

    /** Nothing can move. */
    NONE,
}

/**
 * What the permission screen shows, and what it is allowed to claim.
 *
 * The rules live here rather than in Compose because the honesty question on
 * this screen is the same one as everywhere else in the app: a resident who
 * declines a permission must be told *exactly* what their phone can and cannot
 * now do, not shown a generic warning and left to guess.
 *
 * @see docs/02-roles-permissions.md
 */
data class PermissionUiState(
    val granted: Set<MeshPermission>,
    val permanentlyDenied: Set<MeshPermission>,
    val capability: MeshCapability,
    val headline: String,
    val explanation: String,
    val consequence: String?,
    val primaryLabel: String,
    val canContinueWithoutGranting: Boolean,
    val needsSettings: Boolean,
) {
    val isFullyGranted: Boolean get() = capability == MeshCapability.FULL
}

object PermissionStateFactory {

    /**
     * Why the app wants the radio, in the resident's terms.
     *
     * Android shows its own terse system dialog. This is the sentence that has
     * to make somebody willing to say yes to it, and "Bluetooth permission
     * required" is not that sentence.
     */
    const val RATIONALE =
        "Bulig uses Bluetooth to pass emergency reports between phones when there " +
            "is no signal. It only looks for other phones running Bulig, and it " +
            "never uses your location for anything else."

    fun build(
        granted: Set<MeshPermission>,
        permanentlyDenied: Set<MeshPermission> = emptySet(),
    ): PermissionUiState {
        val capability = capabilityFor(granted)

        return PermissionUiState(
            granted = granted,
            permanentlyDenied = permanentlyDenied,
            capability = capability,
            headline = headline(capability),
            explanation = RATIONALE,
            consequence = consequence(capability, granted),
            primaryLabel = when {
                capability == MeshCapability.FULL -> "Continue"
                permanentlyDenied.isNotEmpty() -> "Open settings"
                else -> "Allow Bluetooth"
            },
            // Never a hard gate. A phone with no permissions at all can still
            // save a report locally, and refusing to let a resident file one
            // would be a worse failure than relaying nothing.
            canContinueWithoutGranting = true,
            needsSettings = permanentlyDenied.isNotEmpty(),
        )
    }

    /**
     * What the phone can actually do.
     *
     * The distinction between [MeshCapability.OUTGOING_ONLY] and
     * [MeshCapability.FULL] is the one residents are never told by other apps: a
     * phone that cannot advertise can hand its own reports on, but can never be
     * found, so it will never carry anybody else's.
     */
    internal fun capabilityFor(granted: Set<MeshPermission>): MeshCapability = when {
        !granted.contains(MeshPermission.EXCHANGE_REPORTS) -> MeshCapability.NONE
        !granted.contains(MeshPermission.FIND_NEARBY_PHONES) -> MeshCapability.NONE
        !granted.contains(MeshPermission.BE_FOUND_BY_OTHERS) -> MeshCapability.OUTGOING_ONLY
        else -> MeshCapability.FULL
    }

    private fun headline(capability: MeshCapability): String = when (capability) {
        MeshCapability.FULL -> "Your phone can carry reports for your neighbours"
        MeshCapability.OUTGOING_ONLY -> "Your phone can send, but cannot be found"
        MeshCapability.NONE -> "Let Bulig use Bluetooth"
    }

    /**
     * The honest consequence of what has been granted so far.
     *
     * Null when everything is granted: there is no consequence to state, and a
     * reassurance nobody needed is noise on a screen that has a job to do.
     */
    internal fun consequence(
        capability: MeshCapability,
        granted: Set<MeshPermission>,
    ): String? = when (capability) {
        MeshCapability.FULL ->
            if (granted.contains(MeshPermission.SHOW_RELAY_NOTICE)) {
                null
            } else {
                // Not a capability loss, but it changes what the resident sees,
                // so it is still stated rather than hidden.
                "Without notification permission you will not see when Bulig is " +
                    "relaying, though it still will."
            }

        MeshCapability.OUTGOING_ONLY ->
            "Your reports can still travel, but this phone will not be able to " +
                "carry reports for other people — they cannot find it."

        MeshCapability.NONE ->
            "Without this, your reports will stay on this phone until it has " +
                "signal again. You can still write them now."
    }

    /**
     * Which permissions to ask for, given the platform.
     *
     * API 31 split the Bluetooth permissions and let an app declare it is not
     * using them for location. Below that, scanning genuinely required location
     * permission, which is why the legacy path asks for something the app has no
     * interest in.
     */
    fun required(sdkInt: Int): List<MeshPermission> = buildList {
        add(MeshPermission.FIND_NEARBY_PHONES)
        add(MeshPermission.BE_FOUND_BY_OTHERS)
        add(MeshPermission.EXCHANGE_REPORTS)
        if (sdkInt >= 33) add(MeshPermission.SHOW_RELAY_NOTICE)
    }
}
