<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Models\Emergency;
use App\Models\RescueAssignment;
use App\Models\Responder;
use App\Services\AuditLogger;
use App\Services\IncidentStatusService;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Gate;
use RuntimeException;

class AssignmentController extends Controller
{
    /** Assignment status → the incident status it implies. */
    private const INCIDENT_STATUS = [
        'EN_ROUTE' => 'EN_ROUTE',
        'ON_SITE' => 'ON_SITE',
        'RESOLVED' => 'RESOLVED',
    ];

    public function __construct(
        private readonly IncidentStatusService $status,
        private readonly AuditLogger $audit,
    ) {}

    public function store(Request $request): JsonResponse
    {
        Gate::authorize('create', RescueAssignment::class);

        $data = $request->validate([
            'emergency_code' => ['required', 'string', 'exists:emergencies,emergency_code'],
            'responder_id' => ['nullable', 'integer', 'exists:responders,id'],
            'rescue_team_id' => ['nullable', 'integer', 'exists:rescue_teams,id'],
            'notes' => ['nullable', 'string', 'max:1000'],
        ]);

        // Optional keys are absent from the validated array when omitted, so
        // read them defensively rather than indexing directly.
        $responderId = $data['responder_id'] ?? null;
        $teamId = $data['rescue_team_id'] ?? null;

        if (! $responderId && ! $teamId) {
            return response()->json([
                'message' => 'Assign either a responder or a team.',
                'code' => 'ASSIGNEE_REQUIRED',
            ], 422);
        }

        $emergency = Emergency::where('emergency_code', $data['emergency_code'])->firstOrFail();

        if ($emergency->isTerminal()) {
            return response()->json([
                'message' => 'This incident is already closed.',
                'code' => 'INCIDENT_CLOSED',
            ], 422);
        }

        $assignment = DB::transaction(function () use ($data, $emergency, $request, $responderId, $teamId) {
            $assignment = $emergency->assignments()->create([
                'responder_id' => $responderId,
                'rescue_team_id' => $teamId,
                'assigned_by_user_id' => $request->user()->id,
                'status' => 'ASSIGNED',
                'assigned_at' => now(),
                'notes' => $data['notes'] ?? null,
            ]);

            if ($assignment->responder_id) {
                Responder::whereKey($assignment->responder_id)->update(['status' => 'assigned']);
            }

            if (! $emergency->isTerminal() && $emergency->status !== 'ASSIGNED') {
                $this->status->transition($emergency, 'ASSIGNED', 'operator', $request->user());
            }

            $this->audit->record('assignment.created', $assignment, null, $assignment->toArray());

            return $assignment;
        });

        return response()->json(['assignment' => $assignment->fresh(['responder.user', 'team'])], 201);
    }

    public function accept(Request $request, RescueAssignment $assignment): JsonResponse
    {
        Gate::authorize('act', $assignment);

        $assignment->forceFill(['status' => 'ACCEPTED', 'accepted_at' => now()])->save();
        $this->audit->record('assignment.accepted', $assignment);

        return response()->json(['assignment' => $assignment]);
    }

    public function decline(Request $request, RescueAssignment $assignment): JsonResponse
    {
        Gate::authorize('act', $assignment);

        $data = $request->validate(['reason' => ['required', 'string', 'max:255']]);

        DB::transaction(function () use ($assignment, $data) {
            $assignment->forceFill([
                'status' => 'DECLINED',
                'decline_reason' => $data['reason'],
            ])->save();

            if ($assignment->responder_id) {
                Responder::whereKey($assignment->responder_id)->update(['status' => 'available']);
            }

            // The incident returns to the operator's queue rather than sitting
            // silently declined.
            $emergency = $assignment->emergency;
            if (! $emergency->isTerminal() && ! $emergency->assignments()->where('status', '!=', 'DECLINED')->exists()) {
                $this->status->transition($emergency, 'TRIAGED', 'responder', null, 'Assignment declined.');
            }

            $this->audit->record('assignment.declined', $assignment, null, ['reason' => $data['reason']]);
        });

        return response()->json(['assignment' => $assignment->fresh()]);
    }

    public function updateStatus(Request $request, RescueAssignment $assignment): JsonResponse
    {
        Gate::authorize('act', $assignment);

        $data = $request->validate([
            'status' => ['required', 'in:EN_ROUTE,ON_SITE,RESOLVED'],
            'notes' => ['nullable', 'string', 'max:1000'],
            'persons_assisted' => ['nullable', 'integer', 'min:0', 'max:9999'],
        ]);

        $timestampColumn = [
            'EN_ROUTE' => 'en_route_at',
            'ON_SITE' => 'on_site_at',
            'RESOLVED' => 'resolved_at',
        ][$data['status']];

        try {
            DB::transaction(function () use ($assignment, $data, $timestampColumn, $request) {
                $assignment->forceFill([
                    'status' => $data['status'],
                    $timestampColumn => now(),
                    'notes' => $data['notes'] ?? $assignment->notes,
                    'persons_assisted' => $data['persons_assisted'] ?? $assignment->persons_assisted,
                ])->save();

                // The incident and the assignment advance together, in one
                // transaction, so the timeline can never disagree with the record.
                $this->status->transition(
                    $assignment->emergency,
                    self::INCIDENT_STATUS[$data['status']],
                    'responder',
                    $request->user(),
                    $data['notes'] ?? null,
                );

                if ($data['status'] === 'RESOLVED' && $assignment->responder_id) {
                    Responder::whereKey($assignment->responder_id)->update(['status' => 'available']);
                }

                $this->audit->record('assignment.status_changed', $assignment, null, ['status' => $data['status']]);
            });
        } catch (RuntimeException $e) {
            return response()->json(
                ['message' => $e->getMessage(), 'code' => 'INVALID_TRANSITION'],
                422
            );
        }

        return response()->json(['assignment' => $assignment->fresh()]);
    }

    public function mine(Request $request): JsonResponse
    {
        $responder = $request->user()->responder;

        if (! $responder) {
            return response()->json(['assignments' => []]);
        }

        $assignments = RescueAssignment::query()
            ->where(fn ($q) => $q->where('responder_id', $responder->id)
                ->orWhere(fn ($t) => $t->whereNotNull('rescue_team_id')
                    ->where('rescue_team_id', $responder->rescue_team_id)))
            ->when($request->boolean('active'), fn ($q) => $q->whereNotIn(
                'status', RescueAssignment::CLOSED_STATUSES
            ))
            ->with(['emergency.type', 'emergency.location'])
            ->latest('assigned_at')
            ->get();

        return response()->json(['assignments' => $assignments]);
    }
}
