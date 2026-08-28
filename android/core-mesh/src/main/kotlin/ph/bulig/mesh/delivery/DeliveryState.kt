package ph.bulig.mesh.delivery

/**
 * How far a report has actually got.
 *
 * The product's one non-negotiable rule is that the app must never lie about
 * delivery. "Saved on this phone", "passed to 3 nearby phones", and "delivered
 * to the command center" are three different facts, and only the last is
 * delivery.
 *
 * States advance ONLY on evidence, never by inference:
 *
 *   SAVED_LOCAL  written to this device's storage; nobody else has seen it
 *   RELAYED      at least one peer acknowledged taking a copy
 *   DELIVERED    the command center acknowledged receipt
 *   ASSIGNED     a responder was assigned to it
 *   EN_ROUTE     the responder is travelling
 *   ON_SITE      the responder has arrived
 *   RESOLVED     the incident was closed
 *
 * Handing a packet to a peer is NOT delivery. A report can be relayed a dozen
 * times and still never arrive.
 *
 * @see docs/design/README.md — "The one non-negotiable product rule"
 */
enum class DeliveryState {
    SAVED_LOCAL,
    RELAYED,
    DELIVERED,
    ASSIGNED,
    EN_ROUTE,
    ON_SITE,
    RESOLVED;

    /**
     * Whether the command center has actually acknowledged this report.
     *
     * This is the predicate the whole colour rule hangs on: only confirmed
     * states may render green.
     */
    val isConfirmedByCommandCenter: Boolean
        get() = ordinal >= DELIVERED.ordinal

    /** True while the report's fate still depends on the mesh. */
    val isUndelivered: Boolean
        get() = !isConfirmedByCommandCenter

    /**
     * State may only move forward, and only on evidence.
     *
     * Guards against the failure this design is most concerned with: a screen
     * optimistically showing "delivered" because a request was dispatched, or a
     * late-arriving mesh event dragging a resolved report backwards.
     */
    fun canAdvanceTo(next: DeliveryState): Boolean = next.ordinal > ordinal
}

/** The visual role a chip takes. Deliberately not a raw colour. */
enum class DeliveryTone {
    /** Held locally. Nobody has seen it. */
    NEUTRAL,

    /** In motion over the mesh. Explicitly not delivered. */
    IN_MOTION,

    /**
     * Confirmed by the command center.
     *
     * RESERVED. Never used for "sent", "queued", or local success. If this
     * appears while a report is undelivered, that is a bug, not a styling
     * choice.
     */
    CONFIRMED,
}

/**
 * Everything a screen needs to render one delivery state, produced together.
 *
 * Colour, icon, label and sentence come from one place so no screen can
 * assemble a partial or optimistic combination — a green tick beside "not yet
 * delivered", say. The plain-language [sentence] is required because the chip
 * is never the only explanation.
 */
data class DeliveryPresentation(
    val state: DeliveryState,
    val tone: DeliveryTone,
    val icon: String,
    val label: String,
    val sentence: String,
)

/**
 * The single source of truth for how a delivery state looks and reads.
 *
 * Build this once; use it on every surface. Screens must not branch on
 * [DeliveryState] themselves.
 */
object DeliveryFormatter {

    fun present(
        state: DeliveryState,
        hopCount: Int = 0,
        responderName: String? = null,
    ): DeliveryPresentation = when (state) {

        DeliveryState.SAVED_LOCAL -> DeliveryPresentation(
            state = state,
            tone = DeliveryTone.NEUTRAL,
            icon = "smartphone",
            label = "SAVED ON THIS PHONE",
            sentence = "No phone has taken a copy yet. It stays here until one does.",
        )

        // The label carries the negation deliberately: "RELAYED" alone reads as
        // success, and this is the state residents are most likely to misread.
        DeliveryState.RELAYED -> DeliveryPresentation(
            state = state,
            tone = DeliveryTone.IN_MOTION,
            icon = "hub",
            label = if (hopCount > 0) "RELAYED · ${phones(hopCount)}" else "RELAYED",
            sentence = "Copies are travelling. Not yet delivered to the command center.",
        )

        DeliveryState.DELIVERED -> DeliveryPresentation(
            state = state,
            tone = DeliveryTone.CONFIRMED,
            icon = "check_circle",
            label = "DELIVERED",
            sentence = "The command center has it.",
        )

        DeliveryState.ASSIGNED -> DeliveryPresentation(
            state = state,
            tone = DeliveryTone.CONFIRMED,
            icon = "badge",
            label = "RESPONDER ASSIGNED",
            sentence = responderName
                ?.let { "The command center has it. $it is assigned." }
                ?: "The command center has it. A responder is assigned.",
        )

        DeliveryState.EN_ROUTE -> DeliveryPresentation(
            state = state,
            tone = DeliveryTone.CONFIRMED,
            icon = "directions_walk",
            label = "RESPONDER ON THE WAY",
            sentence = responderName
                ?.let { "$it is on the way to you." }
                ?: "A responder is on the way to you.",
        )

        DeliveryState.ON_SITE -> DeliveryPresentation(
            state = state,
            tone = DeliveryTone.CONFIRMED,
            icon = "location_on",
            label = "RESPONDER ON SITE",
            sentence = responderName
                ?.let { "$it has arrived." }
                ?: "A responder has arrived.",
        )

        DeliveryState.RESOLVED -> DeliveryPresentation(
            state = state,
            tone = DeliveryTone.CONFIRMED,
            icon = "task_alt",
            label = "RESOLVED",
            sentence = "This emergency was closed by the command center.",
        )
    }

    private fun phones(count: Int): String =
        if (count == 1) "1 PHONE" else "$count PHONES"
}

/**
 * The persistent banner's state — the device's connectivity and sync situation,
 * as distinct from any one report's delivery state.
 *
 * Reads from actual radio and sync state, never from intent. It must never
 * optimistically advance: SYNCED means acknowledged receipt, not a dispatched
 * request.
 */
enum class ConnectivityState {
    ONLINE,
    OFFLINE,
    SYNCING,
    PENDING,
    RELAYED,
    SYNCED;

    val tone: DeliveryTone
        get() = when (this) {
            ONLINE, SYNCED -> DeliveryTone.CONFIRMED
            SYNCING, RELAYED -> DeliveryTone.IN_MOTION
            OFFLINE, PENDING -> DeliveryTone.NEUTRAL
        }
}

data class BannerPresentation(
    val state: ConnectivityState,
    val icon: String,
    val eyebrow: String,
    val sentence: String,
)

object BannerFormatter {

    /**
     * PENDING and OFFLINE share a hue, as do RELAYED and SYNCING, so each pair is
     * additionally separated by icon and wording. The set must stay
     * distinguishable in greyscale and by shape alone.
     */
    fun present(state: ConnectivityState, count: Int = 0): BannerPresentation = when (state) {

        ConnectivityState.ONLINE -> BannerPresentation(
            state, "cloud_done", "ONLINE",
            "Connected to command center",
        )

        ConnectivityState.OFFLINE -> BannerPresentation(
            state, "signal_cellular_off", "OFFLINE",
            "Reports are saved and will be relayed to nearby phones",
        )

        ConnectivityState.SYNCING -> BannerPresentation(
            state, "progress_activity", "SYNCING",
            "Uploading ${reports(count)}…",
        )

        ConnectivityState.PENDING -> BannerPresentation(
            state, "hourglass_top", "PENDING",
            "${reports(count).replaceFirstChar { it.uppercase() }} waiting to be delivered",
        )

        // The negation is part of the label, not an afterthought.
        ConnectivityState.RELAYED -> BannerPresentation(
            state, "hub", "RELAYED — NOT DELIVERED",
            "Passed to ${phones(count)}",
        )

        ConnectivityState.SYNCED -> BannerPresentation(
            state, "check_circle", "SYNCED",
            "Delivered to command center",
        )
    }

    private fun reports(count: Int): String =
        if (count == 1) "1 report" else "$count reports"

    private fun phones(count: Int): String =
        if (count == 1) "1 nearby phone" else "$count nearby phones"
}
