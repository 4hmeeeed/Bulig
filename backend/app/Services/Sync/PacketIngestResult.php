<?php

namespace App\Services\Sync;

final class PacketIngestResult
{
    public const ACCEPTED = 'ACCEPTED';
    public const DUPLICATE = 'DUPLICATE';
    public const TTL_EXPIRED_ACCEPTED = 'TTL_EXPIRED_ACCEPTED';
    public const INVALID_HMAC = 'INVALID_HMAC';
    public const REJECTED = 'REJECTED';

    public function __construct(
        public readonly string $packetId,
        public readonly string $status,
        public readonly ?string $emergencyCode = null,
        public readonly ?string $priorityLevel = null,
        public readonly ?string $reason = null,
    ) {}

    public function toArray(): array
    {
        return array_filter([
            'packet_id' => $this->packetId,
            'status' => $this->status,
            'emergency_code' => $this->emergencyCode,
            'priority_level' => $this->priorityLevel,
            'reason' => $this->reason,
        ], fn ($v) => $v !== null);
    }

    public function isAccepted(): bool
    {
        return in_array($this->status, [self::ACCEPTED, self::TTL_EXPIRED_ACCEPTED], true);
    }
}
