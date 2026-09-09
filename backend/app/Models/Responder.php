<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

class Responder extends Model
{
    use HasFactory;

    protected $fillable = [
        'user_id', 'rescue_team_id', 'badge_no', 'specialisation',
        'status', 'last_known_lat', 'last_known_lng', 'last_location_at',
    ];

    protected function casts(): array
    {
        return [
            'last_known_lat' => 'float',
            'last_known_lng' => 'float',
            'last_location_at' => 'datetime',
        ];
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    public function team(): BelongsTo
    {
        return $this->belongsTo(RescueTeam::class, 'rescue_team_id');
    }

    public function assignments(): HasMany
    {
        return $this->hasMany(RescueAssignment::class);
    }
}
