<?php

namespace App\Services\Sync;

use App\Models\Device;
use App\Models\Emergency;
use App\Models\EmergencyPacket;
use App\Models\EmergencyType;
use App\Models\PacketLog;
use App\Models\StatusHistory;
use App\Models\SyncLog;
use App\Services\Priority\PriorityEngine;
use Carbon\Carbon;
use Illuminate\Database\QueryException;
use Illuminate\Support\Facades\DB;

/**
 * Ingests relayed emergency packets.
 *
 * The contract is idempotency: a packet may arrive any number of times, by any
 * number of routes, in any order — including before the emergency it describes
 * has ever been seen. Submitting a batch twice must leave the database exactly
 * as submitting it once did.
 *
 * @see docs/07-offline-sync.md
 */
class PacketIngestService
{
    /** Corrected delays beyond this magnitude indicate an unusable device clock. */
    private const CLOCK_ANOMALY_THRESHOLD_MS = 60 * 60 * 1000;

    public function __construct(
        private readonly PriorityEngine $priority,
        private readonly EmergencyCodeGenerator $codes,
    ) {}

    /**
     * @param  array<int, array<string, mixed>>  $packets
     * @return array<int, PacketIngestResult>
     */
    public function ingestBatch(
        Device $device,
        array $packets,
        ?Carbon $clientClock = null,
        ?SyncLog $syncLog = null,
    ): array {
        $offsetMs = $clientClock ? $clientClock->getTimestampMs() - now()->getTimestampMs() : null;

        $results = [];
        foreach ($packets as $packet) {
            $results[] = $this->ingestOne($device, $packet, $offsetMs, $syncLog);
        }

        return $results;
    }

    private function ingestOne(
        Device $device,
        array $data,
        ?int $offsetMs,
        ?SyncLog $syncLog,
    ): PacketIngestResult {
        $packetId = $data['packet_id'];

        // Transport-layer dedup. The packet_id is minted once at the origin and
        // never rewritten as the packet travels, which is precisely what makes
        // this check able to recognise a packet that looped back around.
        if (EmergencyPacket::where('packet_id', $packetId)->exists()) {
            $this->log($packetId, 'SYNC_ACCEPTED', $device, $data, $syncLog, ['duplicate' => true]);

            return new PacketIngestResult($packetId, PacketIngestResult::DUPLICATE);
        }

        $type = EmergencyType::where('code', $data['payload']['type_code'] ?? null)->first();
        if (! $type) {
            $this->log($packetId, 'SYNC_REJECTED', $device, $data, $syncLog, ['reason' => 'unknown_type']);

            return new PacketIngestResult(
                $packetId, PacketIngestResult::REJECTED, reason: 'Unknown emergency type.'
            );
        }

        $originDevice = Device::where('device_id', $data['origin_device_id'])->first() ?? $device;
        $hmacValid = $this->verifyHmac($originDevice, $data);

        if ($hmacValid === false) {
            // Stored rather than dropped: evidence of tampering is operationally
            // interesting, and the command center surfaces it on the network page.
            $this->storePacket($data, $device, $originDevice, $offsetMs, 'INVALID_HMAC', $hmacValid, $syncLog);
            $this->log($packetId, 'INVALID_HMAC', $device, $data, $syncLog);

            return new PacketIngestResult(
                $packetId, PacketIngestResult::INVALID_HMAC, reason: 'Payload signature failed verification.'
            );
        }

        $ttlExpired = (int) ($data['ttl_remaining'] ?? 0) === 0;

        try {
            return DB::transaction(function () use (
                $data, $device, $originDevice, $offsetMs, $type, $hmacValid, $ttlExpired, $syncLog, $packetId
            ) {
                $status = $ttlExpired ? 'TTL_EXPIRED' : 'ACCEPTED';
                $packet = $this->storePacket($data, $device, $originDevice, $offsetMs, $status, $hmacValid, $syncLog);

                $emergency = $this->upsertEmergency($data, $originDevice, $type, $packet);

                $this->log($packetId, $ttlExpired ? 'TTL_EXPIRED' : 'SYNC_ACCEPTED', $device, $data, $syncLog);

                return new PacketIngestResult(
                    packetId: $packetId,
                    status: $ttlExpired
                        ? PacketIngestResult::TTL_EXPIRED_ACCEPTED
                        : PacketIngestResult::ACCEPTED,
                    emergencyCode: $emergency->emergency_code,
                    priorityLevel: $emergency->priority_level,
                );
            });
        } catch (QueryException $e) {
            // A concurrent sync of the same packet won the unique-constraint race.
            // That is a duplicate, not a failure — report it as such.
            if ($this->isUniqueViolation($e)) {
                return new PacketIngestResult($packetId, PacketIngestResult::DUPLICATE);
            }

            throw $e;
        }
    }

    private function storePacket(
        array $data,
        Device $carrier,
        Device $originDevice,
        ?int $offsetMs,
        string $status,
        ?bool $hmacValid,
        ?SyncLog $syncLog,
    ): EmergencyPacket {
        $payload = $data['payload'] ?? [];

        return EmergencyPacket::create([
            'packet_id' => $data['packet_id'],
            'emergency_uuid' => $data['emergency_id'],
            'origin_device_id' => $originDevice->id,
            'current_device_id' => $carrier->id,
            'hop_count' => $data['hop_count'] ?? 0,
            'ttl_remaining' => $data['ttl_remaining'] ?? 0,
            'ttl_initial' => $data['ttl_initial'] ?? 10,
            'payload_hash' => hash('sha256', json_encode($payload)),
            'hmac' => $data['hmac'] ?? null,
            'hmac_valid' => $hmacValid,
            'payload_bytes' => strlen((string) json_encode($payload)),
            'status' => $status,
            'created_at_device' => $this->parseTime($data['created_at_device'] ?? null),
            'received_at_server' => now(),
            'clock_offset_ms' => $offsetMs,
            'route_path' => $data['route_path'] ?? null,
        ]);
    }

    /**
     * Creates the incident, or records an additional route to one already known.
     *
     * A late packet arriving by a slow route must never resurrect a resolved
     * incident or overwrite an operator's triage. Existing incidents gain routing
     * evidence and nothing else.
     */
    private function upsertEmergency(
        array $data,
        Device $originDevice,
        EmergencyType $type,
        EmergencyPacket $packet,
    ): Emergency {
        $existing = Emergency::where('emergency_id', $data['emergency_id'])->first();

        if ($existing) {
            if ($packet->hop_count < $existing->first_hop_count) {
                $existing->update(['first_hop_count' => $packet->hop_count]);
            }

            return $existing;
        }

        $payload = $data['payload'] ?? [];
        $deviceTime = $this->parseTime($data['created_at_device'] ?? null);
        $delayMs = $packet->correctedDelayMs();

        $emergency = new Emergency([
            'emergency_id' => $data['emergency_id'],
            'emergency_code' => $this->codes->generate(),
            'emergency_type_id' => $type->id,
            'description' => $payload['description'] ?? null,
            'affected_count' => $payload['affected_count'] ?? 1,
            'children_count' => $payload['children_count'] ?? 0,
            'elderly_count' => $payload['elderly_count'] ?? 0,
            'mobility_limited_count' => $payload['mobility_limited_count'] ?? 0,
            'is_life_threatening' => (bool) ($payload['is_life_threatening'] ?? false),
            'vulnerability_notes' => $payload['vulnerability_notes'] ?? null,
            'status' => 'NEW',
            'origin_device_id' => $originDevice->id,
            'reported_by_user_id' => $originDevice->user_id,
            'created_at_device' => $deviceTime,
            'received_at_server' => now(),
            'first_hop_count' => $packet->hop_count,
            'clock_anomaly' => $delayMs !== null
                && ($delayMs < 0 || abs($delayMs) > self::CLOCK_ANOMALY_THRESHOLD_MS),
        ]);
        $emergency->setRelation('type', $type);

        $result = $this->priority->scoreEmergency($emergency);
        $emergency->priority_score = $result->score;
        $emergency->priority_level = $result->level;
        $emergency->priority_breakdown = $result->toBreakdown();
        $emergency->save();

        if (isset($payload['latitude'], $payload['longitude'])) {
            $emergency->location()->create([
                'latitude' => $payload['latitude'],
                'longitude' => $payload['longitude'],
                'accuracy_m' => $payload['accuracy_m'] ?? null,
                'provider' => $payload['location_provider'] ?? 'gps',
                'is_approximate' => ($payload['location_provider'] ?? 'gps') === 'manual',
                'captured_at' => $this->parseTime($payload['captured_at'] ?? null),
            ]);
        }

        StatusHistory::create([
            'emergency_id' => $emergency->id,
            'from_status' => null,
            'to_status' => 'NEW',
            'source' => 'sync',
            'note' => sprintf('Received via %d hop(s).', $packet->hop_count),
            'occurred_at' => now(),
        ]);

        return $emergency;
    }

    /**
     * Verifies origin authenticity. Relays carry the MAC opaquely — they hold no
     * other device's key — so this is the first point at which tampering by an
     * intermediate node can be detected.
     *
     * Returns null when the origin device has never registered: requiring
     * registration before reporting would reintroduce the very internet
     * dependency this architecture exists to remove.
     */
    private function verifyHmac(Device $originDevice, array $data): ?bool
    {
        $provided = $data['hmac'] ?? null;
        $key = $originDevice->hmac_key;

        if (! $provided || ! $key) {
            return null;
        }

        $canonical = $this->canonicalPayload($data);
        $expected = substr(hash_hmac('sha256', $canonical, $key), 0, 32);

        return hash_equals($expected, (string) $provided);
    }

    /**
     * The signed representation deliberately excludes ttl_remaining and hop_count:
     * relays must be able to decrement those without invalidating the origin's
     * signature. See docs/06-ble-protocol.md 6.7.
     */
    private function canonicalPayload(array $data): string
    {
        $payload = $data['payload'] ?? [];
        ksort($payload);

        return implode('|', [
            $data['packet_id'],
            $data['emergency_id'],
            $data['origin_device_id'] ?? '',
            $data['created_at_device'] ?? '',
            json_encode($payload),
        ]);
    }

    private function log(
        string $packetId,
        string $event,
        Device $device,
        array $data,
        ?SyncLog $syncLog,
        array $detail = [],
    ): void {
        PacketLog::create([
            'packet_id' => $packetId,
            'sync_log_id' => $syncLog?->id,
            'device_id' => $device->id,
            'event' => $event === 'SYNC_ACCEPTED' && ($detail['duplicate'] ?? false)
                ? 'DUPLICATE_SUPPRESSED'
                : $event,
            'hop_count' => $data['hop_count'] ?? null,
            'ttl_remaining' => $data['ttl_remaining'] ?? null,
            'detail' => $detail ?: null,
            'occurred_at' => now(),
        ]);
    }

    private function parseTime(?string $value): ?Carbon
    {
        return $value ? Carbon::parse($value) : null;
    }

    private function isUniqueViolation(QueryException $e): bool
    {
        return in_array($e->getCode(), ['23000', '23505'], true);
    }
}
