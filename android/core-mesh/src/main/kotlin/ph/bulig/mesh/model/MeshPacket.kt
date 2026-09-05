package ph.bulig.mesh.model

/**
 * One emergency in transit.
 *
 * The split between immutable identity and mutable routing header is the single
 * most important decision in the protocol:
 *
 *  - [packetId] is minted once at the origin and NEVER rewritten. Every node
 *    keeps a seen-set keyed on it, which is what allows a packet that has looped
 *    back around to be recognised and dropped. If each hop minted a fresh id,
 *    the seen-set would never match and packets would circulate until their TTL
 *    burned out — turning an optimisation into a broadcast storm.
 *  - [hopCount] and [ttlRemaining] change at every hop, and are deliberately
 *    excluded from the signed bytes so a relay can decrement them without
 *    invalidating the origin's signature.
 *
 * @see docs/06-ble-protocol.md 6.5, 6.7
 */
data class MeshPacket(
    val packetId: PacketId,
    val emergencyId: EmergencyId,
    val originDeviceId: DeviceId,
    val createdAtDeviceMs: Long,
    val payload: EmergencyPayload,
    val hmac: String? = null,
    val hopCount: Int = 0,
    val ttlRemaining: Int = DEFAULT_TTL,
    val ttlInitial: Int = DEFAULT_TTL,
    val routePath: List<DeviceId> = emptyList(),
) {
    init {
        require(hopCount >= 0) { "hop count cannot be negative" }
        require(ttlRemaining >= 0) { "ttl cannot be negative" }
        require(ttlInitial >= 1) { "initial ttl must be at least 1" }
    }

    /** A packet at TTL 0 has reached the end of its journey through the mesh. */
    val isTerminal: Boolean get() = ttlRemaining == 0

    /**
     * Applies one hop.
     *
     * Note what does NOT change: [packetId], [emergencyId], [originDeviceId],
     * the payload, and the signature. A relay is a carrier, not an author.
     */
    fun relayedTo(carrier: DeviceId): MeshPacket = copy(
        hopCount = hopCount + 1,
        ttlRemaining = (ttlRemaining - 1).coerceAtLeast(0),
        routePath = routePath + carrier,
    )

    companion object {
        const val DEFAULT_TTL: Int = 10
    }
}
