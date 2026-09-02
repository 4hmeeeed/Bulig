package ph.bulig.data.presentation

import ph.bulig.data.model.LocalReport

/** How well this phone can currently hear a peer. Observed, not promised. */
enum class LinkQuality(val label: String) {
    STRONG("strong link"),
    WEAK("weak link"),
    UNKNOWN("link unknown"),
}

/**
 * What a peer is worth to a packet right now.
 *
 * [CAN_UPLOAD] is the only role that ends a report's journey rather than
 * extending it, which is why the design gives it the one green tint on this
 * screen. That is consistent with green being reserved: a peer that genuinely
 * reports internet is *confirmed connected*, not merely hoped-for.
 */
enum class PeerRole(val label: String) {
    CAN_UPLOAD("CAN UPLOAD"),
    RELAYING("RELAYING"),
}

/**
 * One nearby device, as this phone sees it.
 *
 * [pseudonym] is a rotating random id that changes daily. No resident name,
 * number, or position is ever available here — the app scans only for its own
 * service UUID, so it does not learn who is nearby, only that *someone running
 * Bulig* is.
 */
data class NearbyPeer(
    val pseudonym: String,
    val linkQuality: LinkQuality = LinkQuality.UNKNOWN,
    /** Null when the peer has not said, which is common and not an error. */
    val hopsFromSignal: Int? = null,
    val hasInternet: Boolean = false,
) {
    val role: PeerRole get() = if (hasInternet) PeerRole.CAN_UPLOAD else PeerRole.RELAYING

    /** "strong link · 2 hops from signal", or an honest admission of not knowing. */
    val description: String
        get() = "${linkQuality.label} · " + when (hopsFromSignal) {
            null -> "hops unknown"
            0 -> "has signal now"
            1 -> "1 hop from signal"
            else -> "$hopsFromSignal hops from signal"
        }
}

/** A peer that has gone. Shown because churn is normal, not because it is a fault. */
data class DepartedPeer(val pseudonym: String, val secondsAgo: Long)

/**
 * Artboard 09 — the emotional core.
 *
 * The purpose of this screen is not telemetry. It is to show a resident that
 * their phone is *helping their neighbours*, in a sentence, without asking them
 * to understand a mesh. The design is explicit that the headline sentence comes
 * first and must not be turned into a stat tile.
 *
 * Everything here is derived from what this device actually observed. There is
 * no map of neighbours' positions and no estimate of coverage, because the app
 * does not know either and inventing them would be the same kind of lie as a
 * premature delivery tick.
 *
 * @see docs/design/HANDOFF.md — artboard 09
 */
data class MeshStatusState(
    val isActive: Boolean,
    val nearbyCount: Int,
    val rangeNote: String,
    val headline: String,
    val reassurance: String,
    val carriedForOthers: Int,
    val passedOnToday: Int,
    val deliveredBecauseOfYou: Int,
    val peers: List<NearbyPeer>,
    val churnNote: String?,
    val privacyNote: String,
) {
    val hasPeers: Boolean get() = peers.isNotEmpty()

    /** True when this phone is doing the thing the screen exists to celebrate. */
    val isHelping: Boolean get() = carriedForOthers > 0
}

object MeshStatusStateFactory {

    /** Stated because BLE range is short and the screen must not imply otherwise. */
    const val RANGE_NOTE = "within about 80 metres"

    const val PRIVACY_NOTE =
        "Device names are random and change daily — no resident names, " +
            "numbers or locations are ever shown."

    fun build(
        carriedForOthers: List<LocalReport>,
        peers: List<NearbyPeer>,
        passedOnToday: Int,
        deliveredBecauseOfYou: Int,
        isRadioActive: Boolean,
        departed: DepartedPeer? = null,
    ): MeshStatusState {
        val carrying = carriedForOthers.size

        return MeshStatusState(
            isActive = isRadioActive,
            nearbyCount = peers.size,
            rangeNote = RANGE_NOTE,
            headline = headline(carrying),
            reassurance = reassurance(carrying, isRadioActive),
            carriedForOthers = carrying,
            passedOnToday = passedOnToday,
            deliveredBecauseOfYou = deliveredBecauseOfYou,
            // Peers that can end a journey are listed first: this screen is also
            // how a resident learns it is worth walking towards someone.
            peers = peers.sortedWith(
                compareByDescending<NearbyPeer> { it.hasInternet }
                    .thenBy { it.hopsFromSignal ?: Int.MAX_VALUE }
            ),
            churnNote = departed?.let {
                "${it.pseudonym} dropped out of range ${it.secondsAgo} s ago."
            },
            privacyNote = PRIVACY_NOTE,
        )
    }

    /**
     * The sentence the whole screen is built around.
     *
     * Second person and plain: "You are carrying 2 reports for other people".
     * Not "2 packets queued for relay", which describes the same fact and means
     * nothing to the person holding the phone.
     */
    internal fun headline(carrying: Int): String = when (carrying) {
        0 -> "Your phone is ready to carry reports for other people"
        1 -> "You are carrying 1 report for someone else"
        else -> "You are carrying $carrying reports for other people"
    }

    /**
     * The follow-on line, and the only place the app tells a resident to do
     * nothing — which is the correct instruction. Relaying is automatic, and a
     * resident who thinks they must keep the screen open will drain the battery
     * the mesh depends on.
     */
    internal fun reassurance(carrying: Int, isRadioActive: Boolean): String = when {
        !isRadioActive ->
            "Bluetooth is off, so this phone cannot carry reports for anyone right now."

        carrying == 0 ->
            "Nothing to carry yet. If a neighbour files a report nearby, your phone " +
                "will pick it up on its own."

        else ->
            "Your phone is holding your neighbours' emergencies and will hand them on " +
                "the moment it can. You do not have to do anything."
    }
}
