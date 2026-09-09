<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class EmergencyLocation extends Model
{
    use HasFactory;

    protected $fillable = [
        'emergency_id', 'latitude', 'longitude', 'accuracy_m',
        'provider', 'is_approximate', 'captured_at',
    ];

    protected function casts(): array
    {
        return [
            'latitude' => 'float',
            'longitude' => 'float',
            'accuracy_m' => 'float',
            'is_approximate' => 'boolean',
            'captured_at' => 'datetime',
        ];
    }

    public function emergency(): BelongsTo
    {
        return $this->belongsTo(Emergency::class);
    }
}
