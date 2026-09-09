<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Builder;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Illuminate\Database\Eloquent\Relations\HasOne;
use Illuminate\Database\Eloquent\SoftDeletes;

class Emergency extends Model
{
    use HasFactory, SoftDeletes;

    /** Statuses after which an incident no longer needs coordination. */
    public const TERMINAL_STATUSES = ['RESOLVED', 'CANCELLED', 'DUPLICATE'];

    protected $fillable = [
        'emergency_id', 'emergency_code', 'emergency_type_id', 'description',
        'affected_count', 'children_count', 'elderly_count', 'mobility_limited_count',
        'is_life_threatening', 'vulnerability_notes',
        'priority_level', 'priority_score', 'priority_breakdown',
        'priority_overridden_by', 'priority_override_reason',
        'status', 'reported_by_user_id', 'origin_device_id',
        'created_at_device', 'received_at_server', 'first_hop_count',
        'clock_anomaly', 'resolved_at',
    ];

    protected function casts(): array
    {
        return [
            'is_life_threatening' => 'boolean',
            'clock_anomaly' => 'boolean',
            'priority_breakdown' => 'array',
            'created_at_device' => 'datetime',
            'received_at_server' => 'datetime',
            'resolved_at' => 'datetime',
        ];
    }

    public function type(): BelongsTo
    {
        return $this->belongsTo(EmergencyType::class, 'emergency_type_id');
    }

    public function location(): HasOne
    {
        return $this->hasOne(EmergencyLocation::class);
    }

    public function reporter(): BelongsTo
    {
        return $this->belongsTo(User::class, 'reported_by_user_id');
    }

    public function originDevice(): BelongsTo
    {
        return $this->belongsTo(Device::class, 'origin_device_id');
    }

    public function priorityOverriddenBy(): BelongsTo
    {
        return $this->belongsTo(User::class, 'priority_overridden_by');
    }

    public function assignments(): HasMany
    {
        return $this->hasMany(RescueAssignment::class);
    }

    public function statusHistory(): HasMany
    {
        return $this->hasMany(StatusHistory::class)->orderBy('occurred_at');
    }

    /**
     * Packets are linked by business key, not foreign key: a packet can reach the
     * server before the emergency row exists. See docs/04-database-erd.md 4.4.
     */
    public function packets(): HasMany
    {
        return $this->hasMany(EmergencyPacket::class, 'emergency_uuid', 'emergency_id');
    }

    public function isTerminal(): bool
    {
        return in_array($this->status, self::TERMINAL_STATUSES, true);
    }

    public function hasPriorityOverride(): bool
    {
        return $this->priority_overridden_by !== null;
    }

    public function scopeActive(Builder $query): Builder
    {
        return $query->whereNotIn('status', self::TERMINAL_STATUSES);
    }

    public function scopeCritical(Builder $query): Builder
    {
        return $query->where('priority_level', 'CRITICAL');
    }

    /**
     * Highest priority first, then oldest first.
     *
     * Expressed as a CASE rather than MySQL's FIELD() so the same query runs on
     * SQLite, which the test suite uses.
     */
    public function scopeOrderByUrgency(Builder $query): Builder
    {
        return $query
            ->orderByRaw(
                "CASE priority_level
                    WHEN 'CRITICAL' THEN 0
                    WHEN 'HIGH' THEN 1
                    WHEN 'MODERATE' THEN 2
                    ELSE 3
                 END"
            )
            ->orderBy('received_at_server');
    }
}
