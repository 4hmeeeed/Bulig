<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class RescueAssignment extends Model
{
    use HasFactory;

    /** Assignment statuses that no longer occupy a responder. */
    public const CLOSED_STATUSES = ['RESOLVED', 'DECLINED', 'REASSIGNED'];

    protected $fillable = [
        'emergency_id', 'rescue_team_id', 'responder_id', 'assigned_by_user_id',
        'status', 'assigned_at', 'accepted_at', 'en_route_at', 'on_site_at',
        'resolved_at', 'decline_reason', 'notes', 'persons_assisted',
    ];

    protected function casts(): array
    {
        return [
            'assigned_at' => 'datetime',
            'accepted_at' => 'datetime',
            'en_route_at' => 'datetime',
            'on_site_at' => 'datetime',
            'resolved_at' => 'datetime',
        ];
    }

    public function emergency(): BelongsTo
    {
        return $this->belongsTo(Emergency::class);
    }

    public function responder(): BelongsTo
    {
        return $this->belongsTo(Responder::class);
    }

    public function team(): BelongsTo
    {
        return $this->belongsTo(RescueTeam::class, 'rescue_team_id');
    }

    public function assignedBy(): BelongsTo
    {
        return $this->belongsTo(User::class, 'assigned_by_user_id');
    }

    public function isOpen(): bool
    {
        return ! in_array($this->status, self::CLOSED_STATUSES, true);
    }
}
