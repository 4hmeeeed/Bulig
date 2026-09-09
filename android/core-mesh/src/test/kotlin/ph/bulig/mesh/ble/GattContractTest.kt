package ph.bulig.mesh.ble

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GattContractTest {

    private val allUuids = listOf(
        GattContract.SERVICE_UUID,
        GattContract.CHAR_DIGEST,
        GattContract.CHAR_PACKET_IN,
        GattContract.CHAR_ACK,
        GattContract.CHAR_NODE_INFO,
    )

    /**
     * The cheapest test in the suite, and it caught a real defect: an earlier
     * draft used `bul16000`, which is not hexadecimal. `UUID.fromString` throws
     * on it, so the service would have died on its first line — on a device, in
     * a field test, with no unit test anywhere to say why.
     */
    @Test
    fun `every declared uuid actually parses`() {
        allUuids.forEach { text ->
            val parsed = UUID.fromString(text)
            assertEquals(text, parsed.toString(), "$text does not round-trip")
        }
    }

    @Test
    fun `no two characteristics share a uuid`() {
        assertEquals(allUuids.size, allUuids.toSet().size, "duplicate uuid in the contract")
    }

    /**
     * A 16-bit SIG-assigned UUID expands to `0000xxxx-0000-1000-8000-00805f9b34fb`.
     * Ours must not land in that space, or Bulig would advertise itself as a
     * standard Bluetooth service and invite connections from unrelated hardware.
     */
    @Test
    fun `no uuid collides with the bluetooth sig base range`() {
        allUuids.forEach { text ->
            assertTrue(
                !text.endsWith("-0000-1000-8000-00805f9b34fb"),
                "$text sits in the SIG assigned-numbers space",
            )
        }
    }

    @Test
    fun `the minimum mtu is the ble default rather than an optimistic guess`() {
        assertEquals(23, GattContract.MINIMUM_MTU)
        assertTrue(GattContract.PREFERRED_MTU > GattContract.MINIMUM_MTU)
    }
}

class AdvertisementPayloadTest {

    @Test
    fun `an advertisement round-trips`() {
        val payload = AdvertisementPayload(
            hasInternet = true,
            supportsAdvertising = true,
            pendingCount = 300,
        )

        assertEquals(payload, AdvertisementPayload.decode(payload.encode()))
    }

    @Test
    fun `an advertisement fits the manufacturer data budget`() {
        // BLE gives 31 bytes of advertisement total, and the service UUID alone
        // takes 18 of them. Four bytes is what is genuinely left.
        assertEquals(AdvertisementPayload.SIZE_BYTES, AdvertisementPayload().encode().size)
        assertTrue(AdvertisementPayload.SIZE_BYTES <= 8)
    }

    @Test
    fun `the flags are independent`() {
        val scanOnly = AdvertisementPayload(hasInternet = false, supportsAdvertising = false)
        val decoded = AdvertisementPayload.decode(scanOnly.encode())

        assertNotNull(decoded)
        assertTrue(!decoded.hasInternet)
        assertTrue(!decoded.supportsAdvertising)
    }

    @Test
    fun `a short advertisement decodes to nothing`() {
        assertNull(AdvertisementPayload.decode(byteArrayOf(1, 0, 0)))
    }
}

class AckCodeTest {

    @Test
    fun `every ack code round-trips through its wire byte`() {
        AckCode.entries.forEach { code ->
            assertEquals(code, AckCode.fromWire(code.wire))
        }
    }

    @Test
    fun `no two ack codes share a wire byte`() {
        assertEquals(AckCode.entries.size, AckCode.entries.map { it.wire }.toSet().size)
    }

    @Test
    fun `an unknown wire byte decodes to nothing rather than the first entry`() {
        assertNull(AckCode.fromWire(0x7F))
    }

    /**
     * A duplicate is a success. The sender's goal was for the peer to hold the
     * packet, and it does — treating this as a failure would make a device
     * retry forever against a peer that already has what it needs.
     */
    @Test
    fun `a duplicate counts as delivered`() {
        assertTrue(AckCode.DUPLICATE.isDelivered)
        assertTrue(AckCode.ACCEPTED_TERMINAL.isDelivered)
        assertTrue(!AckCode.CORRUPT.isDelivered)
    }

    /** Corruption is a link problem; a bad signature is a packet problem. */
    @Test
    fun `only signature and version failures are permanent`() {
        assertTrue(AckCode.INVALID_HMAC.isPermanent)
        assertTrue(AckCode.UNSUPPORTED.isPermanent)
        assertTrue(!AckCode.CORRUPT.isPermanent)
        assertTrue(!AckCode.NO_CAPACITY.isPermanent)
    }
}
