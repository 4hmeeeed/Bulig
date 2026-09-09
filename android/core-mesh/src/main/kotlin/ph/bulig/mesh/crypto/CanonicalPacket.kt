package ph.bulig.mesh.crypto

import java.math.BigDecimal
import java.math.RoundingMode
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import ph.bulig.mesh.model.MeshPacket

/**
 * The canonical byte string an origin device signs, and the server verifies.
 *
 * THIS IS A CROSS-LANGUAGE CONTRACT. It must produce output byte-identical to
 * `App\Services\Sync\CanonicalPacket` in the Laravel backend. A JSON encoding
 * would not: PHP escapes forward slashes by default, the two languages format
 * floating point differently, and their Unicode escaping rules diverge — so a
 * JSON-signed packet from a real phone would fail verification on arrival.
 *
 * The shared fixture in CanonicalPacketTest pins the two implementations
 * together. If either drifts, a test goes red instead of the field deployment.
 *
 * @see docs/06-ble-protocol.md 6.7
 */
object CanonicalPacket {

    const val VERSION: String = "bulig.canon.v1"

    /** Truncated length of the hex MAC carried on the wire (16 bytes). */
    const val HMAC_HEX_LENGTH: Int = 32

    fun build(packet: MeshPacket): String {
        val p = packet.payload

        return listOf(
            VERSION,
            text(packet.packetId.value),
            text(packet.emergencyId.value),
            text(packet.originDeviceId.value),
            text(packet.createdAtDeviceMs.toString()),

            text(p.typeCode),
            text(p.description),
            p.affectedCount.toString(),
            p.childrenCount.toString(),
            p.elderlyCount.toString(),
            p.mobilityLimitedCount.toString(),
            if (p.isLifeThreatening) "1" else "0",
            text(p.vulnerabilityNotes),

            decimal(p.latitude, scale = 7),
            decimal(p.longitude, scale = 7),
            decimal(p.accuracyM, scale = 2),
            text(p.locationProvider),
            text(p.capturedAtMs?.toString()),
        ).joinToString("\n")
    }

    fun sign(packet: MeshPacket, key: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, "HmacSHA256"))
        }
        return mac.doFinal(build(packet).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(HMAC_HEX_LENGTH)
    }

    fun verify(packet: MeshPacket, key: ByteArray, provided: String): Boolean =
        constantTimeEquals(sign(packet, key), provided)

    /**
     * Length-prefixed, so a description containing a newline cannot impersonate
     * a field boundary. The prefix counts UTF-8 BYTES, matching PHP's strlen.
     */
    private fun text(value: String?): String {
        val v = value ?: ""
        return "${v.toByteArray(Charsets.UTF_8).size}:$v"
    }

    /**
     * Fixed-scale decimal, rendered then length-prefixed as text.
     *
     * BigDecimal.valueOf uses the shortest representation that round-trips the
     * double, which is what PHP's float-to-string conversion also produces for
     * the precision range we accept. HALF_UP matches PHP's round(), which rounds
     * away from zero — important for the negative coordinates this must handle.
     */
    private fun decimal(value: Double?, scale: Int): String {
        if (value == null) return text(null)
        return text(
            BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).toPlainString()
        )
    }

    /** Comparison that does not leak how much of the MAC matched. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
