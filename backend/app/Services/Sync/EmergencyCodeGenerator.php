<?php

namespace App\Services\Sync;

use App\Models\Emergency;
use Illuminate\Support\Facades\DB;

/**
 * Assigns the human-readable incident label (BLG-2026-0417) on first sync.
 *
 * This is the one identifier the server owns. The device-minted UUID is the
 * real identity; this is a short handle an operator can read aloud over a radio.
 */
class EmergencyCodeGenerator
{
    public function generate(?int $year = null): string
    {
        $year ??= (int) now()->year;
        $prefix = "BLG-{$year}-";

        // Serialise allocation so two packets arriving together cannot collide.
        return DB::transaction(function () use ($prefix) {
            $last = Emergency::withTrashed()
                ->where('emergency_code', 'like', $prefix.'%')
                ->lockForUpdate()
                ->orderByDesc('emergency_code')
                ->value('emergency_code');

            $next = $last ? ((int) substr($last, strlen($prefix))) + 1 : 1;

            return $prefix.str_pad((string) $next, 4, '0', STR_PAD_LEFT);
        });
    }
}
