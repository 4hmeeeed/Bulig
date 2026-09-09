package ph.bulig.mesh.model

/**
 * A copy this device gave to a peer, at a time this device actually observed.
 *
 * IMPORTANT — what a device can and cannot know:
 *
 * An offline phone knows only the handoffs it performed **itself**. When phone A
 * gives a report to phone B, A learns "B took a copy at 15:04". A never learns
 * that B later passed it to C: there is no back-channel through an offline mesh.
 *
 * So a resident's own device can honestly show "3 phones took a copy from this
 * phone", but it cannot show a 3-hop chain until the server confirms delivery
 * and reports the true route back. The two are different claims, and the UI must
 * not present the first as the second.
 *
 * @see docs/design/DESIGN-RECONCILIATION.md
 */
data class Handoff(
    /** The peer that acknowledged taking a copy. */
    val peerId: DeviceId,
    /** Observed on this device's clock, which may be wrong — see docs/07-offline-sync.md. */
    val atMs: Long,
)

/**
 * Display name for a peer, rotated daily so a device cannot be tracked across
 * days by the name shown on a neighbour's screen.
 *
 * The underlying [DeviceId] is stable — it has to be, because the server issues
 * a signing key against it and the seen-set keys on packet identity. Only the
 * *shown* name rotates.
 *
 * Never shows a resident's name, number, or location.
 */
object PeerPseudonym {

    /** e.g. "phone-7C4A" — matches the format used across the mesh screens. */
    fun forDevice(deviceId: DeviceId, dayEpoch: Long): String {
        var hash = -0x7EE3623B // FNV-1a offset basis
        val material = "${deviceId.value}:$dayEpoch"

        for (byte in material.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toInt() and 0xFF)
            hash *= 16777619
        }

        val suffix = (hash.toLong() and 0xFFFFL).toString(16).uppercase().padStart(4, '0')
        return "phone-$suffix"
    }

    /** Days since the Unix epoch, in UTC. */
    fun dayEpochOf(nowMs: Long): Long = nowMs / 86_400_000L
}
