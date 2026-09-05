<?php

namespace Tests\Feature;

use App\Services\Sync\CanonicalPacket;
use Tests\TestCase;

/**
 * Pins the cross-language signing contract.
 *
 * The expected MAC below is duplicated verbatim in the Kotlin suite
 * (android/core-mesh/src/test/kotlin/ph/bulig/mesh/CanonicalPacketTest.kt).
 * If either implementation drifts, one of the two suites goes red — instead of
 * every real device's packets silently failing verification in the field.
 *
 * DO NOT "fix" a failure here by editing the constant. Fix the implementation
 * that changed, or bump CanonicalPacket::VERSION deliberately and update both.
 */
class CanonicalPacketTest extends TestCase
{
    /**
     * The shared fixture. Chosen to exercise every awkward case:
     * a description containing a newline, a forward slash and a non-ASCII
     * character; a null field; a negative coordinate; and an accuracy value
     * that needs rounding.
     */
    public const FIXTURE = [
        'packet_id' => '9b1d7c3e-4f2a-4c8b-9e1d-000000000001',
        'emergency_id' => '44ca8e12-7b3d-4a5f-8c2e-000000000002',
        'origin_device_id' => '1f2e3d4c-5b6a-4798-8877-000000000003',
        'created_at_device' => '2026-08-27T03:52:11Z',
        'hop_count' => 3,          // excluded from the signature
        'ttl_remaining' => 7,      // excluded from the signature
        'payload' => [
            'type_code' => 'MEDICAL',
            'description' => "Elderly man collapsed near the creek/bridge.\nHindi humihinga — señor.",
            'affected_count' => 4,
            'children_count' => 0,
            'elderly_count' => 2,
            'mobility_limited_count' => 1,
            'is_life_threatening' => true,
            'vulnerability_notes' => null,
            'latitude' => 11.2447,
            'longitude' => -125.0038125,
            'accuracy_m' => 12.456,
            'location_provider' => 'gps',
            'captured_at' => '2026-08-27T03:52:09Z',
        ],
    ];

    public const KEY_HEX = '000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f';

    /**
     * Regenerate ONLY by deliberate decision, and update the Kotlin copy too.
     *
     * sha256 of the canonical string itself, for cross-checking without the key:
     * b9926d0b0ef15c5073bf9975d581cc20ef3e862f672251d8697e398c498f6df4
     */
    public const EXPECTED_HMAC = 'f8c462f8b8f3d32fa09a8431202b448b';

    public function test_the_shared_fixture_produces_the_agreed_signature(): void
    {
        $this->assertSame(
            self::EXPECTED_HMAC,
            CanonicalPacket::sign(self::FIXTURE, hex2bin(self::KEY_HEX)),
            'The canonical signing contract changed. Update the Kotlin implementation '
            .'and its matching fixture, or this will silently break every real device.'
        );
    }

    public function test_mutable_relay_fields_do_not_affect_the_signature(): void
    {
        $relayed = self::FIXTURE;
        $relayed['hop_count'] = 9;
        $relayed['ttl_remaining'] = 1;

        // A relay must be able to decrement TTL without invalidating the origin's
        // signature; that is what lets it carry a packet it cannot forge.
        $this->assertSame(
            CanonicalPacket::sign(self::FIXTURE, hex2bin(self::KEY_HEX)),
            CanonicalPacket::sign($relayed, hex2bin(self::KEY_HEX)),
        );
    }

    public function test_altering_the_payload_changes_the_signature(): void
    {
        $tampered = self::FIXTURE;
        $tampered['payload']['affected_count'] = 40;

        $this->assertNotSame(
            CanonicalPacket::sign(self::FIXTURE, hex2bin(self::KEY_HEX)),
            CanonicalPacket::sign($tampered, hex2bin(self::KEY_HEX)),
        );
    }

    /**
     * Length prefixing exists so a description cannot impersonate field
     * boundaries. Without it, these two packets would canonicalise identically.
     */
    public function test_field_boundaries_cannot_be_forged_from_within_a_text_field(): void
    {
        $a = self::FIXTURE;
        $a['payload']['description'] = "abc";
        $a['payload']['vulnerability_notes'] = "def";

        $b = self::FIXTURE;
        $b['payload']['description'] = "abc\n3:def";
        $b['payload']['vulnerability_notes'] = null;

        $this->assertNotSame(
            CanonicalPacket::sign($a, hex2bin(self::KEY_HEX)),
            CanonicalPacket::sign($b, hex2bin(self::KEY_HEX)),
        );
    }

    public function test_timestamps_are_normalised_across_equivalent_representations(): void
    {
        $utc = self::FIXTURE;
        $offset = self::FIXTURE;
        // The same instant, expressed in Philippine local time.
        $offset['created_at_device'] = '2026-08-27T11:52:11+08:00';

        $this->assertSame(
            CanonicalPacket::sign($utc, hex2bin(self::KEY_HEX)),
            CanonicalPacket::sign($offset, hex2bin(self::KEY_HEX)),
        );
    }

    public function test_null_and_absent_fields_canonicalise_identically(): void
    {
        $explicitNull = self::FIXTURE;
        $explicitNull['payload']['vulnerability_notes'] = null;

        $absent = self::FIXTURE;
        unset($absent['payload']['vulnerability_notes']);

        $this->assertSame(
            CanonicalPacket::build($explicitNull),
            CanonicalPacket::build($absent),
        );
    }
}
