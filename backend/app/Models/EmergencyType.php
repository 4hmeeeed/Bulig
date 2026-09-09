<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\HasMany;

class EmergencyType extends Model
{
    use HasFactory;

    protected $fillable = [
        'code', 'label_en', 'label_war', 'icon',
        'base_severity', 'is_life_threatening', 'sort_order', 'is_active',
    ];

    protected function casts(): array
    {
        return [
            'is_life_threatening' => 'boolean',
            'is_active' => 'boolean',
        ];
    }

    public function emergencies(): HasMany
    {
        return $this->hasMany(Emergency::class);
    }
}
