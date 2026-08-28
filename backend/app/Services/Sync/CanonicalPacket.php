<?php

namespace App\Services\Sync;

use Carbon\Carbon;

/**
 * The canonical byte string an origin device signs, and the server verifies.
 *
 * This is a CROSS-LANGUAGE CONTRACT: Kotlin on the phone and PHP on the server
 * must produce byte-identical output, or every real packet fails verification.
 * It is therefore defined explicitly rather than delegated to a JSON encoder —
 * PHP's json_encode escapes forward slashes, formats floats, and escapes
 * Unicode in ways Kotlin's encoders do not reproduce.
 *
 * Rules, all chosen so both languages can implement them without ambiguity:
 *
 *   - Fields appear in a FIXED order. Never sorted, never derived from a map.
 *   - Text is length-prefixed as "<utf8ByteLength>:<text>", so no delimiter can
 *     be injected by a description containing newlines or pipes.
 *   - Timestamps are epoch MILLISECONDS, never formatted date strings.
 *   - Booleans are "1" / "0".
 *   - Coordinates are fixed-scale decimal strings (7 places for lat/lng,
 *     2 for accuracy). Devices must not send more than 7 decimal places of
 *     coordinate precision — roughly 11 mm, far beyond any phone GPS.
 *   - Null and absent are both the empty text field, "0:".
 *   - ttl_remaining and hop_count are DELIBERATELY EXCLUDED: relays must be able
 *     to decrement them without invalidating the origin's signature.
 *
 * @see docs/06-ble-protocol.md 6.7
 */
final class CanonicalPacket
{
    /** Bump when the field list or encoding rules change. */
    public const VERSION = 'bulig.canon.v1';

    /** Truncated length of the hex MAC carried on the wire (16 bytes). */
    public const HMAC_HEX_LENGTH = 32;

    public static function build(array $packet): string
    {
        $payload = $packet['payload'] ?? [];

        $fields = [
            self::VERSION,
            self::text($packet['packet_id'] ?? null),
            self::text($packet['emergency_id'] ?? null),
            self::text($packet['origin_device_id'] ?? null),
            self::epochMs($packet['created_at_device'] ?? null),

            self::text($payload['type_code'] ?? null),
            self::text($payload['description'] ?? null),
            self::int($payload['affected_count'] ?? 0),
            self::int($payload['children_count'] ?? 0),
            self::int($payload['elderly_count'] ?? 0),
            self::int($payload['mobility_limited_count'] ?? 0),
            self::bool($payload['is_life_threatening'] ?? false),
            self::text($payload['vulnerability_notes'] ?? null),

            self::decimal($payload['latitude'] ?? null, 7),
            self::decimal($payload['longitude'] ?? null, 7),
            self::decimal($payload['accuracy_m'] ?? null, 2),
            self::text($payload['location_provider'] ?? null),
            self::epochMs($payload['captured_at'] ?? null),
        ];

        return implode("\n", $fields);
    }

    public static function sign(array $packet, string $key): string
    {
        return substr(
            hash_hmac('sha256', self::build($packet), $key),
            0,
            self::HMAC_HEX_LENGTH
        );
    }

    public static function verify(array $packet, string $key, string $provided): bool
    {
        return hash_equals(self::sign($packet, $key), $provided);
    }

    private static function text(?string $value): string
    {
        $value ??= '';

        return strlen($value).':'.$value;
    }

    private static function int(mixed $value): string
    {
        return (string) (int) $value;
    }

    private static function bool(mixed $value): string
    {
        return $value ? '1' : '0';
    }

    private static function decimal(mixed $value, int $scale): string
    {
        if ($value === null || $value === '') {
            return self::text(null);
        }

        return self::text(number_format((float) $value, $scale, '.', ''));
    }

    private static function epochMs(mixed $value): string
    {
        if (empty($value)) {
            return self::text(null);
        }

        return self::text((string) Carbon::parse($value)->getTimestampMs());
    }
}
