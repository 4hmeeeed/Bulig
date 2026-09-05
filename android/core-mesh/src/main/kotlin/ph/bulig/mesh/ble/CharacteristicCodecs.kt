package ph.bulig.mesh.ble

import ph.bulig.mesh.digest.BloomDigest
import ph.bulig.mesh.model.DeviceId

/**
 * What a peer says about itself when NODE_INFO is read.
 *
 * Read before anything is offered, because two of these fields change what the
 * session does: a mismatched protocol version ends the encounter, and
 * [hasInternet] decides whether this peer is worth preferring over another.
 */
data class NodeInfo(
    val deviceId: DeviceId,
    val protocolVersion: Int = GattContract.PROTOCOL_VERSION,
    val hasInternet: Boolean = false,
    val pendingCount: Int = 0,
)

/**
 * Wire encoding for the readable characteristics.
 *
 * Lives here rather than in the Android service for the same reason
 * [PacketCodec] does: a codec that can only run with a radio attached is a codec
 * that never gets tested, and both ends of this wire are different builds on
 * different phones.
 *
 * Every decoder returns null rather than throwing. A peer that sends something
 * unparseable is a peer to skip, not a crash in a foreground service that is
 * meant to keep running through a disaster.
 *
 * @see docs/06-ble-protocol.md 6.2
 */
object NodeInfoCodec {

    /**
     * ```
     * [version:1][flags:1][pendingCount:2][idLength:1][deviceId:idLength]
     * ```
     *
     * The id is length-prefixed rather than terminated, so a device id
     * containing any byte at all still decodes to exactly itself.
     */
    const val HEADER_BYTES = 5

    /** A UUID string is 36 bytes; the cap leaves room without inviting abuse. */
    const val MAX_ID_BYTES = 64

    fun encode(info: NodeInfo): ByteArray {
        val id = info.deviceId.value.toByteArray(Charsets.UTF_8)
        require(id.size <= MAX_ID_BYTES) { "device id exceeds $MAX_ID_BYTES bytes" }

        var flags = 0
        if (info.hasInternet) flags = flags or AdvertisementPayload.FLAG_HAS_INTERNET

        val pending = info.pendingCount.coerceIn(0, 0xFFFF)

        return byteArrayOf(
            info.protocolVersion.toByte(),
            flags.toByte(),
            ((pending shr 8) and 0xFF).toByte(),
            (pending and 0xFF).toByte(),
            id.size.toByte(),
        ) + id
    }

    fun decode(bytes: ByteArray): NodeInfo? {
        if (bytes.size < HEADER_BYTES) return null

        val idLength = bytes[4].toInt() and 0xFF
        // A length that overruns the buffer means a truncated read, not a short
        // id — decoding the fragment would invent a peer identity.
        if (idLength == 0 || bytes.size < HEADER_BYTES + idLength) return null

        val id = String(bytes, HEADER_BYTES, idLength, Charsets.UTF_8)
        if (id.isBlank()) return null

        val flags = bytes[1].toInt() and 0xFF

        return NodeInfo(
            deviceId = DeviceId(id),
            protocolVersion = bytes[0].toInt() and 0xFF,
            hasInternet = flags and AdvertisementPayload.FLAG_HAS_INTERNET != 0,
            pendingCount = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF),
        )
    }
}

/**
 * Wire encoding for the Bloom digest characteristic.
 *
 * The hash count travels with the bits. Reading a filter built with a different
 * k and probing it with our own would return answers that are wrong in the
 * unsafe direction — false negatives look like "peer is missing this", which is
 * merely wasteful, but false *positives* from a mismatched k would silently
 * suppress delivery.
 */
object DigestCodec {

    const val HEADER_BYTES = 1

    fun encode(digest: BloomDigest, hashCount: Int = BloomDigest.DEFAULT_HASH_COUNT): ByteArray =
        byteArrayOf(hashCount.toByte()) + digest.toByteArray()

    /**
     * Returns null for an empty or header-only read.
     *
     * Null is not "the peer holds nothing" — the session decides what to assume,
     * and it deliberately assumes the emptier, more generous thing.
     */
    fun decode(bytes: ByteArray): BloomDigest? {
        if (bytes.size <= HEADER_BYTES) return null

        val hashCount = bytes[0].toInt() and 0xFF
        if (hashCount == 0) return null

        return BloomDigest.fromByteArray(
            bytes.copyOfRange(HEADER_BYTES, bytes.size),
            hashCount = hashCount,
        )
    }
}
