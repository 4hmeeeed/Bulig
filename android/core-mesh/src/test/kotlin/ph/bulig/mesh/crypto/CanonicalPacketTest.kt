package ph.bulig.mesh.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import ph.bulig.mesh.model.DeviceId
import ph.bulig.mesh.model.EmergencyId
import ph.bulig.mesh.model.EmergencyPayload
import ph.bulig.mesh.model.MeshPacket
import ph.bulig.mesh.model.PacketId

/**
 * The other half of the cross-language signing contract.
 *
 * [EXPECTED_HMAC] is duplicated verbatim from the PHP suite
 * (backend/tests/Feature/CanonicalPacketTest.php). If Kotlin and PHP ever
 * disagree about how a packet is canonicalised, one of these two suites goes red
 * — instead of every real device's packets silently failing verification once
 * the app is in the field.
 *
 * DO NOT "fix" a failure here by editing the constant. Fix whichever
 * implementation changed, or bump the version deliberately in both.
 *
 * @see docs/06-ble-protocol.md 6.7.3
 */
class CanonicalPacketTest {

    private companion object {
        /**
         * The shared fixture. Chosen to exercise every awkward case: a
         * description containing a newline, a forward slash (which PHP's
         * json_encode would have escaped and Kotlin's would not) and non-ASCII
         * characters; a null field; a negative coordinate; and an accuracy
         * value that needs rounding.
         */
        val FIXTURE = MeshPacket(
            packetId = PacketId("9b1d7c3e-4f2a-4c8b-9e1d-000000000001"),
            emergencyId = EmergencyId("44ca8e12-7b3d-4a5f-8c2e-000000000002"),
            originDeviceId = DeviceId("1f2e3d4c-5b6a-4798-8877-000000000003"),
            // 2026-08-27T03:52:11Z
            createdAtDeviceMs = 1787802731000,
            hopCount = 3,       // excluded from the signature
            ttlRemaining = 7,   // excluded from the signature
            payload = EmergencyPayload(
                typeCode = "MEDICAL",
                description = "Elderly man collapsed near the creek/bridge.\nHindi humihinga — señor.",
                affectedCount = 4,
                childrenCount = 0,
                elderlyCount = 2,
                mobilityLimitedCount = 1,
                isLifeThreatening = true,
                vulnerabilityNotes = null,
                latitude = 11.2447,
                longitude = -125.0038125,
                accuracyM = 12.456,
                locationProvider = "gps",
                capturedAtMs = 1787802729000, // 2026-08-27T03:52:09Z
            ),
        )

        val KEY: ByteArray = ByteArray(32) { it.toByte() }

        /** Must match backend/tests/Feature/CanonicalPacketTest.php exactly. */
        const val EXPECTED_HMAC = "f8c462f8b8f3d32fa09a8431202b448b"

        /** sha256 of the canonical string, for cross-checking without the key. */
        const val EXPECTED_CANONICAL_SHA256 =
            "b9926d0b0ef15c5073bf9975d581cc20ef3e862f672251d8697e398c498f6df4"
    }

    private fun sha256(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * THE test. Everything else in this project can work perfectly and the mesh
     * still fails if this one does not hold.
     */
    @Test
    fun `the shared fixture produces the signature the server expects`() {
        assertEquals(
            EXPECTED_HMAC,
            CanonicalPacket.sign(FIXTURE, KEY),
            "Kotlin and PHP disagree about packet canonicalisation. Every real " +
                "device's packets would be rejected as INVALID_HMAC. Fix the " +
                "implementation that changed — do not edit this constant.",
        )
    }

    @Test
    fun `the canonical string itself matches the server byte for byte`() {
        assertEquals(EXPECTED_CANONICAL_SHA256, sha256(CanonicalPacket.build(FIXTURE)))
    }

    /**
     * A relay must be able to decrement TTL without invalidating the origin's
     * signature. That is exactly what lets an untrusted phone carry a report it
     * cannot forge.
     */
    @Test
    fun `relaying a packet does not invalidate its signature`() {
        val relayed = FIXTURE
            .relayedTo(DeviceId("aaaaaaaa-0000-4000-8000-000000000009"))
            .relayedTo(DeviceId("bbbbbbbb-0000-4000-8000-00000000000a"))

        assertEquals(5, relayed.hopCount)
        assertEquals(5, relayed.ttlRemaining)
        assertEquals(
            CanonicalPacket.sign(FIXTURE, KEY),
            CanonicalPacket.sign(relayed, KEY),
            "hop count and TTL are deliberately outside the signed bytes",
        )
    }

    @Test
    fun `altering the payload changes the signature`() {
        val tampered = FIXTURE.copy(payload = FIXTURE.payload.copy(affectedCount = 40))

        assertNotEquals(CanonicalPacket.sign(FIXTURE, KEY), CanonicalPacket.sign(tampered, KEY))
    }

    /**
     * Length prefixing exists so a description cannot impersonate a field
     * boundary. Without it these two packets would canonicalise identically.
     */
    @Test
    fun `field boundaries cannot be forged from inside a text field`() {
        val a = FIXTURE.copy(
            payload = FIXTURE.payload.copy(description = "abc", vulnerabilityNotes = "def")
        )
        val b = FIXTURE.copy(
            payload = FIXTURE.payload.copy(description = "abc\n3:def", vulnerabilityNotes = null)
        )

        assertNotEquals(CanonicalPacket.sign(a, KEY), CanonicalPacket.sign(b, KEY))
    }

    @Test
    fun `verification accepts a good signature and rejects a bad one`() {
        val signed = PacketSigner(KEY).sign(FIXTURE)

        assertTrue(CanonicalPacket.verify(signed, KEY, signed.hmac!!))
        assertFalse(CanonicalPacket.verify(signed, KEY, "0".repeat(32)))
        assertFalse(
            CanonicalPacket.verify(signed, ByteArray(32) { 9 }, signed.hmac!!),
            "another device's key must not verify this packet",
        )
    }

    /**
     * A device that has never reached the server has no key. It must still be
     * able to report — requiring registration first would reintroduce the very
     * internet dependency this architecture exists to remove.
     */
    @Test
    fun `an unregistered device still produces a usable packet`() {
        val signer = PacketSigner(null)

        assertFalse(signer.canSign)
        val packet = signer.sign(FIXTURE.copy(hmac = null))

        assertEquals(null, packet.hmac, "the server records this as unverifiable, not invalid")
        assertEquals(FIXTURE.packetId, packet.packetId)
    }

    @Test
    fun `signing is stable across repeated calls`() {
        assertEquals(CanonicalPacket.sign(FIXTURE, KEY), CanonicalPacket.sign(FIXTURE, KEY))
    }
}
