<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

class EmergencyPacket extends Model
{
    use HasFactory;

    protected $fillable = [
        'packet_id', 'emergency_uuid', 'origin_device_id', 'current_device_id',
        'hop_count', 'ttl_remaining', 'ttl_initial',
        'payload_hash', 'hmac', 'hmac_valid', 'payload_bytes', 'status',
        'created_at_device', 'received_at_server', 'clock_offset_ms', 'route_path',
    ];

    protected function casts(): array
    {
        return [
            'hmac_valid' => 'boolean',
            'route_path' => 'array',
            'created_at_device' => 'datetime',
            'received_at_server' => 'datetime',
        ];
    }

    public function emergency(): BelongsTo
    {
        return $this->belongsTo(Emergency::class, 'emergency_uuid', 'emergency_id');
    }

    public function originDevice(): BelongsTo
    {
        return $this->belongsTo(Device::class, 'origin_device_id');
    }

    public function currentDevice(): BelongsTo
    {
        return $this->belongsTo(Device::class, 'current_device_id');
    }

    public function logs(): HasMany
    {
        return $this->hasMany(PacketLog::class, 'packet_id', 'packet_id');
    }

    /**
     * Transmission delay corrected for device clock drift.
     *
     * An offline phone's clock can be minutes or hours off. Without correcting
     * for the offset measured at sync, this figure is meaningless — and a
     * fast clock produces a negative delay. See docs/07-offline-sync.md 7.6.
     */
    public function correctedDelayMs(): ?int
    {
        if (! $this->created_at_device || ! $this->received_at_server) {
            return null;
        }

        $originMs = $this->created_at_device->getTimestampMs() - ($this->clock_offset_ms ?? 0);

        return $this->received_at_server->getTimestampMs() - $originMs;
    }
}
