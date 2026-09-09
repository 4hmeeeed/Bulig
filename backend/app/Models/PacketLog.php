<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class PacketLog extends Model
{
    use HasFactory;

    protected $fillable = [
        'packet_id', 'sync_log_id', 'device_id', 'event',
        'hop_count', 'ttl_remaining', 'detail', 'occurred_at',
    ];

    protected function casts(): array
    {
        return [
            'detail' => 'array',
            'occurred_at' => 'datetime',
        ];
    }

    public function device(): BelongsTo
    {
        return $this->belongsTo(Device::class);
    }

    public function syncLog(): BelongsTo
    {
        return $this->belongsTo(SyncLog::class);
    }
}
