<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class StatusHistory extends Model
{
    use HasFactory;

    protected $table = 'status_history';

    protected $fillable = [
        'emergency_id', 'from_status', 'to_status',
        'changed_by_user_id', 'source', 'note', 'occurred_at',
    ];

    protected function casts(): array
    {
        return ['occurred_at' => 'datetime'];
    }

    public function emergency(): BelongsTo
    {
        return $this->belongsTo(Emergency::class);
    }

    public function changedBy(): BelongsTo
    {
        return $this->belongsTo(User::class, 'changed_by_user_id');
    }
}
