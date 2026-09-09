<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\HasMany;

class RescueTeam extends Model
{
    use HasFactory;

    protected $fillable = [
        'name', 'code', 'contact_number', 'base_location',
        'base_latitude', 'base_longitude', 'is_active',
    ];

    protected function casts(): array
    {
        return [
            'is_active' => 'boolean',
            'base_latitude' => 'float',
            'base_longitude' => 'float',
        ];
    }

    public function responders(): HasMany
    {
        return $this->hasMany(Responder::class);
    }

    public function assignments(): HasMany
    {
        return $this->hasMany(RescueAssignment::class);
    }
}
