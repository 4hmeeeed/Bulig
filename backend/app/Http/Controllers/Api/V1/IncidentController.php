<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Models\Emergency;
use App\Services\AuditLogger;
use App\Services\IncidentStatusService;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Gate;
use RuntimeException;

class IncidentController extends Controller
{
    public function __construct(
        private readonly IncidentStatusService $status,
        private readonly AuditLogger $audit,
    ) {}

    public function index(Request $request): JsonResponse
    {
        Gate::authorize('viewAny', Emergency::class);

        $incidents = Emergency::query()
            ->with(['type:id,code,label_en,icon', 'location'])
            ->when($request->filled('status'), fn ($q) => $q->where('status', $request->string('status')))
            ->when($request->filled('priority'), fn ($q) => $q->where('priority_level', $request->string('priority')))
            ->when($request->filled('type'), fn ($q) => $q->whereRelation('type', 'code', $request->string('type')))
            ->when($request->filled('from'), fn ($q) => $q->where('received_at_server', '>=', $request->date('from')))
            ->when($request->filled('to'), fn ($q) => $q->where('received_at_server', '<=', $request->date('to')))
            ->when($request->filled('q'), fn ($q) => $q->where(fn ($w) => $w
                ->where('emergency_code', 'like', '%'.$request->string('q').'%')
                ->orWhere('description', 'like', '%'.$request->string('q').'%')))
            // Responders see only their own assignments — enforced in the query,
            // not just in the policy, so a list endpoint cannot leak by omission.
            ->when($request->user()->hasRole('responder'), function ($q) use ($request) {
                $responder = $request->user()->responder;
                $q->whereHas('assignments', fn ($a) => $a
                    ->where('responder_id', $responder?->id)
                    ->orWhere(fn ($t) => $t->whereNotNull('rescue_team_id')
                        ->where('rescue_team_id', $responder?->rescue_team_id)));
            })
            ->orderByUrgency()
            ->paginate($request->integer('per_page', 20));

        return response()->json($incidents);
    }

    public function show(Request $request, string $code): JsonResponse
    {
        $emergency = Emergency::where('emergency_code', $code)
            ->with(['type', 'location', 'originDevice:id,device_id,label',
                'assignments.responder.user:id,name', 'assignments.team'])
            ->firstOrFail();

        Gate::authorize('view', $emergency);

        return response()->json([
            'incident' => $emergency,
            'packets' => $emergency->packets()->get([
                'packet_id', 'hop_count', 'ttl_remaining', 'ttl_initial',
                'status', 'hmac_valid', 'created_at_device', 'received_at_server',
                'clock_offset_ms', 'route_path',
            ]),
        ]);
    }

    public function timeline(Request $request, string $code): JsonResponse
    {
        $emergency = Emergency::where('emergency_code', $code)->firstOrFail();
        Gate::authorize('view', $emergency);

        return response()->json([
            'status_history' => $emergency->statusHistory()->with('changedBy:id,name')->get(),
            'packet_events' => \App\Models\PacketLog::whereIn(
                'packet_id', $emergency->packets()->pluck('packet_id')
            )->orderBy('occurred_at')->get(),
        ]);
    }

    public function updateStatus(Request $request, string $code): JsonResponse
    {
        $emergency = Emergency::where('emergency_code', $code)->firstOrFail();
        Gate::authorize('updateStatus', $emergency);

        $data = $request->validate([
            'status' => ['required', 'string', 'max:20'],
            'note' => ['nullable', 'string', 'max:255'],
        ]);

        try {
            $emergency = $this->status->transition(
                $emergency,
                $data['status'],
                $request->user()->hasRole('responder') ? 'responder' : 'operator',
                $request->user(),
                $data['note'] ?? null,
            );
        } catch (RuntimeException $e) {
            return response()->json(
                ['message' => $e->getMessage(), 'code' => 'INVALID_TRANSITION'],
                422
            );
        }

        return response()->json(['incident' => $emergency->fresh()]);
    }

    public function updatePriority(Request $request, string $code): JsonResponse
    {
        $emergency = Emergency::where('emergency_code', $code)->firstOrFail();

        $data = $request->validate([
            'level' => ['required', 'in:LOW,MODERATE,HIGH,CRITICAL'],
            'reason' => ['required', 'string', 'min:5', 'max:255'],
        ]);

        if (! Gate::allows('overridePriority', [$emergency, $data['level']])) {
            return response()->json([
                'message' => 'Lowering incident priority requires a barangay official.',
                'code' => 'PRIORITY_LOWER_FORBIDDEN',
            ], 403);
        }

        $before = ['priority_level' => $emergency->priority_level];

        $emergency->forceFill([
            'priority_level' => $data['level'],
            'priority_overridden_by' => $request->user()->id,
            'priority_override_reason' => $data['reason'],
        ])->save();

        $this->audit->record('incident.priority_overridden', $emergency, $before, [
            'priority_level' => $data['level'],
            'reason' => $data['reason'],
        ]);

        return response()->json(['incident' => $emergency->fresh()]);
    }

    /**
     * Marker-only payload. The full record is a second request on click, so a
     * command center on a barangay connection is not made to download every
     * description just to draw pins.
     */
    public function map(Request $request): JsonResponse
    {
        Gate::authorize('viewAny', Emergency::class);

        $features = Emergency::query()
            ->active()
            ->with(['location', 'type:id,code,icon'])
            ->get()
            ->filter(fn (Emergency $e) => $e->location !== null)
            ->map(fn (Emergency $e) => [
                'type' => 'Feature',
                'geometry' => [
                    'type' => 'Point',
                    'coordinates' => [$e->location->longitude, $e->location->latitude],
                ],
                'properties' => [
                    'code' => $e->emergency_code,
                    'type' => $e->type->code,
                    'icon' => $e->type->icon,
                    'priority' => $e->priority_level,
                    'status' => $e->status,
                    'accuracy_m' => $e->location->accuracy_m,
                    'hops' => $e->first_hop_count,
                ],
            ])->values();

        return response()->json(['type' => 'FeatureCollection', 'features' => $features]);
    }
}
