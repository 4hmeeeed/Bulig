<?php

namespace Tests\Support;

use App\Models\Device;
use Illuminate\Support\Str;

/**
 * Builds sync payloads shaped exactly like the ones the Android client sends,
 * including a correctly computed HMAC, so ingestion tests exercise the real
 * contract rather than a convenient approximation of it.
 */
class PacketBuilder
{
    private array $data;

    public function __construct(private readonly Device $origin)
    {
        $this->data = [
            'packet_id' => Str::uuid()->toString(),
            'emergency_id' => Str::uuid()->toString(),
            'origin_device_id' => $origin->device_id,
            'hop_count' => 0,
            'ttl_remaining' => 10,
            'ttl_initial' => 10,
            'created_at_device' => now()->subMinutes(5)->toIso8601String(),
            'payload' => [
                'type_code' => 'MEDICAL',
                'description' => 'Test emergency',
                'affected_count' => 1,
                'children_count' => 0,
                'elderly_count' => 0,
                'mobility_limited_count' => 0,
                'is_life_threatening' => false,
                'latitude' => 11.2447,
                'longitude' => 125.0038,
                'accuracy_m' => 10.0,
                'location_provider' => 'gps',
            ],
        ];
    }

    public static function for(Device $origin): self
    {
        return new self($origin);
    }

    public function with(array $attributes): self
    {
        $this->data = array_replace($this->data, $attributes);

        return $this;
    }

    public function payload(array $attributes): self
    {
        $this->data['payload'] = array_replace($this->data['payload'], $attributes);

        return $this;
    }

    public function hops(int $hops, ?int $ttlRemaining = null): self
    {
        $this->data['hop_count'] = $hops;
        $this->data['ttl_remaining'] = $ttlRemaining ?? max(0, $this->data['ttl_initial'] - $hops);

        return $this;
    }

    /** Signs the packet the way the origin device would. */
    public function signed(): self
    {
        $this->data['hmac'] = $this->computeHmac($this->origin->hmac_key);

        return $this;
    }

    /** Signs with the wrong key, standing in for a relay that altered the payload. */
    public function tampered(): self
    {
        $this->data['hmac'] = $this->computeHmac(random_bytes(32));

        return $this;
    }

    private function computeHmac(string $key): string
    {
        $payload = $this->data['payload'];
        ksort($payload);

        $canonical = implode('|', [
            $this->data['packet_id'],
            $this->data['emergency_id'],
            $this->data['origin_device_id'],
            $this->data['created_at_device'],
            json_encode($payload),
        ]);

        return substr(hash_hmac('sha256', $canonical, $key), 0, 32);
    }

    public function toArray(): array
    {
        return $this->data;
    }

    public function batch(): array
    {
        return [
            'client_clock' => now()->toIso8601String(),
            'packets' => [$this->data],
        ];
    }
}
