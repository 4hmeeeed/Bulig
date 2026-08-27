<?php

namespace App\Services;

use App\Models\Emergency;
use App\Models\StatusHistory;
use App\Models\User;
use Illuminate\Support\Facades\DB;
use RuntimeException;

/**
 * Applies incident status transitions, keeping the timeline and the incident
 * row in step. Status changes always go through here so that no code path can
 * advance an incident without leaving a trace.
 */
class IncidentStatusService
{
    /** Transitions the coordination workflow permits. */
    private const ALLOWED = [
        'NEW' => ['TRIAGED', 'ASSIGNED', 'CANCELLED', 'DUPLICATE'],
        'TRIAGED' => ['ASSIGNED', 'CANCELLED', 'DUPLICATE'],
        'ASSIGNED' => ['EN_ROUTE', 'ON_SITE', 'TRIAGED', 'CANCELLED'],
        'EN_ROUTE' => ['ON_SITE', 'RESOLVED', 'ASSIGNED'],
        'ON_SITE' => ['RESOLVED', 'EN_ROUTE'],
        'RESOLVED' => [],
        'CANCELLED' => [],
        'DUPLICATE' => [],
    ];

    public function __construct(private readonly AuditLogger $audit) {}

    public function canTransition(string $from, string $to): bool
    {
        return in_array($to, self::ALLOWED[$from] ?? [], true);
    }

    public function transition(
        Emergency $emergency,
        string $to,
        string $source = 'operator',
        ?User $actor = null,
        ?string $note = null,
    ): Emergency {
        $from = $emergency->status;

        if ($from === $to) {
            return $emergency;
        }

        if (! $this->canTransition($from, $to)) {
            throw new RuntimeException("Cannot move incident from {$from} to {$to}.");
        }

        return DB::transaction(function () use ($emergency, $from, $to, $source, $actor, $note) {
            $emergency->status = $to;
            if ($to === 'RESOLVED') {
                $emergency->resolved_at = now();
            }
            $emergency->save();

            StatusHistory::create([
                'emergency_id' => $emergency->id,
                'from_status' => $from,
                'to_status' => $to,
                'changed_by_user_id' => $actor?->id,
                'source' => $source,
                'note' => $note,
                'occurred_at' => now(),
            ]);

            $this->audit->record('incident.status_changed', $emergency,
                ['status' => $from], ['status' => $to, 'note' => $note]);

            return $emergency;
        });
    }
}
