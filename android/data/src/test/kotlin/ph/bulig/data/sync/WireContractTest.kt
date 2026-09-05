package ph.bulig.data.sync

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import ph.bulig.data.model.LocalReport
import ph.bulig.mesh.model.DeviceId
import ph.bulig.mesh.model.EmergencyId
import ph.bulig.mesh.model.EmergencyPayload
import ph.bulig.mesh.model.MeshPacket
import ph.bulig.mesh.model.PacketId

/**
 * The shape of what goes on the wire.
 *
 * These assertions cover the Kotlin half. The other half is a script that takes
 * the JSON this test writes to `build/contract/sync-request.json` and POSTs it to
 * a running Laravel server, so the real `SyncPacketsRequest` validator gets the
 * final say. Reading both files and hoping they agree is how the HMAC
 * canonicalisation bug survived as long as it did.
 *
 * @see docs/05-api-contract.md 5.2
 */
class WireContractTest {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val sample = LocalReport(
        packet = MeshPacket(
            packetId = PacketId("9b1d7c3e-4f2a-4c8b-9e1d-000000000001"),
            emergencyId = EmergencyId("44ca8e12-7b3d-4a5f-8c2e-000000000002"),
            originDeviceId = DeviceId("1f2e3d4c-5b6a-4798-8877-000000000003"),
            createdAtDeviceMs = 1_787_802_731_000,
            payload = EmergencyPayload(
                typeCode = "MEDICAL",
                description = "Elderly man collapsed near the creek/bridge.",
                affectedCount = 4,
                childrenCount = 0,
                elderlyCount = 2,
                mobilityLimitedCount = 1,
                isLifeThreatening = true,
                latitude = 11.2447,
                longitude = 125.0038,
                accuracyM = 12.4,
                locationProvider = "gps",
                capturedAtMs = 1_787_802_729_000,
            ),
            hopCount = 3,
            ttlRemaining = 7,
            ttlInitial = 10,
            routePath = listOf(
                DeviceId("1f2e3d4c-5b6a-4798-8877-000000000003"),
                DeviceId("77bbccdd-0000-4000-8000-000000000004"),
            ),
        ),
    )

    /**
     * Writes the request the Android client would actually send, for the
     * end-to-end script to feed to the live validator.
     */
    @Test
    fun `emit a sync request for the cross language contract check`() {
        val request = SyncRequestDto(
            clientClock = Iso8601.format(1_787_802_760_000),
            packets = listOf(sample.toDto()),
        )

        val out = File("build/contract").apply { mkdirs() }
            .resolve("sync-request.json")
        out.writeText(json.encodeToString(SyncRequestDto.serializer(), request))

        assertTrue(out.exists() && out.length() > 0)
    }

    /** Field names are snake_case because the Laravel validator addresses them that way. */
    @Test
    fun `serialised field names match the api contract`() {
        val encoded = json.encodeToString(
            SyncRequestDto.serializer(),
            SyncRequestDto(Iso8601.format(1_787_802_760_000), listOf(sample.toDto())),
        )

        listOf(
            "client_clock", "packet_id", "emergency_id", "origin_device_id",
            "hop_count", "ttl_remaining", "ttl_initial", "created_at_device",
            "route_path", "type_code", "affected_count", "children_count",
            "elderly_count", "mobility_limited_count", "is_life_threatening",
            "accuracy_m", "location_provider", "captured_at",
        ).forEach {
            assertTrue(encoded.contains("\"$it\""), "missing wire field: $it")
        }

        // A camelCase leak means the DTO lost a @SerialName and validation would
        // silently reject the field as absent.
        listOf("packetId", "hopCount", "typeCode", "isLifeThreatening").forEach {
            assertFalse(encoded.contains("\"$it\""), "camelCase leaked onto the wire: $it")
        }
    }

    /** Timestamps must be the ISO-8601 UTC form Carbon parses on the server. */
    @Test
    fun `timestamps are iso 8601 utc`() {
        val dto = sample.toDto()

        assertEquals("2026-08-27T03:52:11Z", dto.createdAtDevice)
        assertEquals("2026-08-27T03:52:09Z", dto.payload.capturedAt)
        assertEquals(1_787_802_731_000, Iso8601.parse(dto.createdAtDevice))
    }

    @Test
    fun `route path is omitted rather than sent empty`() {
        val noRoute = sample.copy(packet = sample.packet.copy(routePath = emptyList()))

        assertEquals(null, noRoute.toDto().routePath)
        assertEquals(2, sample.toDto().routePath?.size)
    }

    @Test
    fun `responses parse including unknown future fields`() {
        val body = """
            {
              "server_time": "2026-08-27T04:10:02Z",
              "clock_offset_ms": -1200,
              "sync_log_id": 3312,
              "results": [
                {"packet_id": "9b1d7c3e-4f2a-4c8b-9e1d-000000000001",
                 "status": "ACCEPTED",
                 "emergency_code": "BLG-2026-0417",
                 "priority_level": "CRITICAL"}
              ],
              "summary": {"accepted": 1, "duplicate": 0, "rejected": 0},
              "some_field_added_later": true
            }
        """.trimIndent()

        val lenient = Json { ignoreUnknownKeys = true }
        val parsed = lenient.decodeFromString(SyncResponseDto.serializer(), body)

        assertEquals(-1200, parsed.clockOffsetMs)
        assertEquals("BLG-2026-0417", parsed.results.single().emergencyCode)
        assertEquals(1, parsed.summary?.accepted)
    }

    @Test
    fun `every documented outcome maps to a known enum value`() {
        listOf(
            "ACCEPTED" to PacketOutcome.ACCEPTED,
            "DUPLICATE" to PacketOutcome.DUPLICATE,
            "TTL_EXPIRED_ACCEPTED" to PacketOutcome.TTL_EXPIRED_ACCEPTED,
            "INVALID_HMAC" to PacketOutcome.INVALID_HMAC,
            "REJECTED" to PacketOutcome.REJECTED,
        ).forEach { (wire, expected) ->
            assertEquals(expected, PacketOutcome.fromWire(wire))
        }

        // An unrecognised status must not be mistaken for success.
        val unknown = PacketOutcome.fromWire("SOMETHING_NEW")
        assertEquals(PacketOutcome.UNKNOWN, unknown)
        assertFalse(unknown.isDelivered)
        assertFalse(unknown.isPermanent, "an unknown status is retried, not abandoned")
    }
}
