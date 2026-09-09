<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

class SyncLog extends Model
{
    use HasFactory;

    protected $fillable = [
        'device_id', 'direction', 'packets_sent', 'packets_accepted',
        'packets_duplicate', 'packets_rejected', 'bytes',
        'started_at', 'completed_at', 'duration_ms', 'outcome', 'error',
        'ip_address', 'client_clock_at_start',
    ];

    protected function casts(): array
    {
        return [
            'started_at' => 'datetime',
            'completed_at' => 'datetime',
            'client_clock_at_start' => 'datetime',
        ];
    }

    public function device(): BelongsTo
    {
        return $this->belongsTo(Device::class);
    }

    public function packetLogs(): HasMany
    {
        return $this->hasMany(PacketLog::class);
    }
}
