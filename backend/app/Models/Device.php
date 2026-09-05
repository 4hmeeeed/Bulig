<?php

namespace App\Models;

use Illuminate\Auth\Authenticatable as AuthenticatableTrait;
use Illuminate\Contracts\Auth\Authenticatable;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Laravel\Sanctum\HasApiTokens;

class Device extends Model implements Authenticatable
{
    // A device is itself a token holder: sync tokens are scoped to packet
    // transfer and grant no access to incident data.
    // Authenticatable so a device token can pass through the standard auth and
    // throttle middleware. A device is a token holder, never a person.
    use AuthenticatableTrait, HasApiTokens, HasFactory;

    protected $fillable = [
        'device_id', 'user_id', 'label', 'model', 'android_version',
        'hmac_key', 'supports_advertising', 'is_revoked',
        'last_battery_pct', 'last_seen_at', 'last_sync_at',
    ];

    protected $hidden = ['hmac_key'];

    protected function casts(): array
    {
        return [
            'supports_advertising' => 'boolean',
            'is_revoked' => 'boolean',
            'last_seen_at' => 'datetime',
            'last_sync_at' => 'datetime',
        ];
    }

    /** A short identifier for the UI: the operator label, else the device UUID stem. */
    public function displayName(): string
    {
        return $this->label ?: substr($this->device_id, 0, 8);
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    public function originatedPackets(): HasMany
    {
        return $this->hasMany(EmergencyPacket::class, 'origin_device_id');
    }

    public function syncLogs(): HasMany
    {
        return $this->hasMany(SyncLog::class);
    }
}
