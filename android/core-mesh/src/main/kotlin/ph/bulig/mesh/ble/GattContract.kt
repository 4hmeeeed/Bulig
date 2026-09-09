package ph.bulig.mesh.ble

/**
 * The BLE wire contract: UUIDs, advertisement layout, and acknowledgement codes.
 *
 * Kept in `:core-mesh` rather than the Android module so the codecs are testable
 * without a radio. Android's `BluetoothGattServer` plumbing consumes these; it
 * does not define them.
 *
 * @see docs/06-ble-protocol.md 6.2
 */
object GattContract {

    /**
     * The Bulig service UUID.
     *
     * A scanning device filters on this so it wakes only for its own kind — that
     * filter is also what lets the manifest claim `neverForLocation`, since the
     * app learns nothing about devices that are not running Bulig.
     *
     * TO BE VALIDATED before pilot deployment: this is a randomly generated
     * 128-bit UUID, but it has not been checked against the Bluetooth SIG's
     * assigned-numbers list. A collision would make Bulig devices connect to
     * unrelated hardware.
     *
     * An earlier draft spelled these with `bul16000`, which is not hexadecimal;
     * `UUID.fromString` would have thrown on the service's first line.
     */
    const val SERVICE_UUID = "03aa6000-8bad-4f9a-9c1e-1000c0de6000"

    /** Peer's Bloom-filtered set of held packet ids. Read, Notify. */
    const val CHAR_DIGEST = "03aa6001-8bad-4f9a-9c1e-1000c0de6000"

    /** Inbound chunk stream. Write, Write-No-Response. */
    const val CHAR_PACKET_IN = "03aa6002-8bad-4f9a-9c1e-1000c0de6000"

    /** Per-packet acknowledgement. Notify. */
    const val CHAR_ACK = "03aa6003-8bad-4f9a-9c1e-1000c0de6000"

    /** Device id, protocol version, connectivity flag. Read. */
    const val CHAR_NODE_INFO = "03aa6004-8bad-4f9a-9c1e-1000c0de6000"

    const val PROTOCOL_VERSION: Int = 1

    /** Requested ATT MTU. Peripherals routinely grant less; never assume this. */
    const val PREFERRED_MTU: Int = 517

    /**
     * Fallback when negotiation fails.
     *
     * 23 is the BLE default, leaving 20 usable bytes after the ATT header. Small
     * enough that a report needs many fragments, which is exactly why the
     * framing layer exists.
     */
    const val MINIMUM_MTU: Int = 23

    /** Most devices cap concurrent GATT connections well below this. */
    const val MAX_CONCURRENT_CONNECTIONS: Int = 3
}

/**
 * The four bytes of manufacturer data in a Bulig advertisement.
 *
 * ```
 * [version:1][flags:1][pendingCount:2]
 * ```
 *
 * The `HAS_INTERNET` flag is the highest-value byte in the protocol: it lets a
 * scanner prefer a peer that can actually reach the server, which ends a
 * packet's journey rather than merely extending it.
 */
data class AdvertisementPayload(
    val protocolVersion: Int = GattContract.PROTOCOL_VERSION,
    val hasInternet: Boolean = false,
    val supportsAdvertising: Boolean = true,
    val pendingCount: Int = 0,
) {
    fun encode(): ByteArray {
        var flags = 0
        if (hasInternet) flags = flags or FLAG_HAS_INTERNET
        if (supportsAdvertising) flags = flags or FLAG_CAN_ADVERTISE

        // Clamped rather than wrapped: a device holding more than 65535 packets
        // is a bug, and advertising "3" for 65539 would hide it.
        val pending = pendingCount.coerceIn(0, 0xFFFF)

        return byteArrayOf(
            protocolVersion.toByte(),
            flags.toByte(),
            ((pending shr 8) and 0xFF).toByte(),
            (pending and 0xFF).toByte(),
        )
    }

    companion object {
        const val SIZE_BYTES = 4
        const val FLAG_HAS_INTERNET = 0x01
        const val FLAG_CAN_ADVERTISE = 0x02

        /** Returns null for anything malformed — a peer we cannot parse is skipped. */
        fun decode(bytes: ByteArray): AdvertisementPayload? {
            if (bytes.size < SIZE_BYTES) return null

            val flags = bytes[1].toInt() and 0xFF

            return AdvertisementPayload(
                protocolVersion = bytes[0].toInt() and 0xFF,
                hasInternet = flags and FLAG_HAS_INTERNET != 0,
                supportsAdvertising = flags and FLAG_CAN_ADVERTISE != 0,
                pendingCount = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF),
            )
        }
    }
}

/**
 * What a receiver reports back about one packet.
 *
 * A sender needs to know whether to stop offering a packet to this peer, and
 * whether the failure was about the packet or about the link.
 */
enum class AckCode(val wire: Byte) {
    /** Stored, and it will be relayed onward. */
    ACCEPTED(0x01),

    /** Stored, but at TTL 0 — it stops here, though it can still be synced. */
    ACCEPTED_TERMINAL(0x02),

    /** Already held. Not a failure: the peer has it, which is what mattered. */
    DUPLICATE(0x03),

    /** CRC mismatch. The link garbled it; retrying may work. */
    CORRUPT(0x04),

    /** Origin signature failed. Retrying cannot help. */
    INVALID_HMAC(0x05),

    /** Protocol version this device cannot parse. */
    UNSUPPORTED(0x06),

    /** Receiver is out of room. Back off rather than hammering it. */
    NO_CAPACITY(0x07);

    /** The peer holds the packet — however it got there. */
    val isDelivered: Boolean
        get() = this == ACCEPTED || this == ACCEPTED_TERMINAL || this == DUPLICATE

    /** Offering this packet to this peer again would produce the same answer. */
    val isPermanent: Boolean
        get() = this == INVALID_HMAC || this == UNSUPPORTED

    companion object {
        fun fromWire(value: Byte): AckCode? = entries.firstOrNull { it.wire == value }
    }
}
