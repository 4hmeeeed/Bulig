<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Http\Requests\Api\SyncPacketsRequest;
use App\Models\EmergencyType;
use App\Models\RescueAssignment;
use App\Models\SyncLog;
use App\Services\Priority\PriorityConfig;
use App\Services\Sync\PacketIngestResult;
use App\Services\Sync\PacketIngestService;
use Carbon\Carbon;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

class SyncController extends Controller
{
    public function __construct(private readonly PacketIngestService $ingest) {}

    /**
     * Accepts a batch of relayed packets.
     *
     * Always answers 200 for a well-formed batch, with per-packet outcomes in the
     * body. A transport-level error would force the client to re-send packets the
     * server already holds, so partial success is reported rather than thrown.
     */
    public function push(SyncPacketsRequest $request): JsonResponse
    {
        $device = $request->user();
        $data = $request->validated();
        $clientClock = Carbon::parse($data['client_clock']);
        $startedAt = now();

        $syncLog = SyncLog::create([
            'device_id' => $device->id,
            'direction' => 'push',
            'packets_sent' => count($data['packets']),
            'bytes' => strlen((string) json_encode($data['packets'])),
            'started_at' => $startedAt,
            'ip_address' => $request->ip(),
            'client_clock_at_start' => $clientClock,
        ]);

        $results = $this->ingest->ingestBatch($device, $data['packets'], $clientClock, $syncLog);

        $accepted = collect($results)->filter->isAccepted()->count();
        $duplicate = collect($results)
            ->where('status', PacketIngestResult::DUPLICATE)->count();
        $rejected = count($results) - $accepted - $duplicate;

        $syncLog->forceFill([
            'packets_accepted' => $accepted,
            'packets_duplicate' => $duplicate,
            'packets_rejected' => $rejected,
            'completed_at' => now(),
            'duration_ms' => (int) $startedAt->diffInMilliseconds(now()),
            'outcome' => $rejected === 0 ? 'success' : ($accepted > 0 ? 'partial' : 'failed'),
        ])->save();

        $device->forceFill(['last_sync_at' => now(), 'last_seen_at' => now()])->save();

        return response()->json([
            'server_time' => now()->toIso8601String(),
            'clock_offset_ms' => $clientClock->getTimestampMs() - now()->getTimestampMs(),
            'sync_log_id' => $syncLog->id,
            'results' => array_map(fn (PacketIngestResult $r) => $r->toArray(), $results),
            'summary' => [
                'accepted' => $accepted,
                'duplicate' => $duplicate,
                'rejected' => $rejected,
            ],
        ]);
    }

    /**
     * Returns assignment and configuration changes since the client's last sync.
     */
    public function pull(Request $request): JsonResponse
    {
        $device = $request->user();
        $since = $request->date('since') ?? now()->subDay();

        $assignments = RescueAssignment::query()
            ->when($device->user_id, fn ($q) => $q->whereHas(
                'responder', fn ($r) => $r->where('user_id', $device->user_id)
            ))
            ->when(! $device->user_id, fn ($q) => $q->whereRaw('1 = 0'))
            ->where('updated_at', '>=', $since)
            ->with(['emergency.type', 'emergency.location'])
            ->orderBy('updated_at')
            ->limit(100)
            ->get()
            ->map(fn (RescueAssignment $a) => [
                'assignment_id' => $a->id,
                'status' => $a->status,
                'assigned_at' => $a->assigned_at?->toIso8601String(),
                'emergency' => [
                    'code' => $a->emergency->emergency_code,
                    'type' => $a->emergency->type->code,
                    'description' => $a->emergency->description,
                    'priority_level' => $a->emergency->priority_level,
                    'status' => $a->emergency->status,
                    'affected_count' => $a->emergency->affected_count,
                    'children_count' => $a->emergency->children_count,
                    'elderly_count' => $a->emergency->elderly_count,
                    'mobility_limited_count' => $a->emergency->mobility_limited_count,
                    'is_life_threatening' => $a->emergency->is_life_threatening,
                    'latitude' => $a->emergency->location?->latitude,
                    'longitude' => $a->emergency->location?->longitude,
                ],
            ]);

        $device->forceFill(['last_seen_at' => now()])->save();

        return response()->json([
            'server_time' => now()->toIso8601String(),
            'assignments' => $assignments,
            'emergency_types' => EmergencyType::where('is_active', true)
                ->orderBy('sort_order')
                ->get(['code', 'label_en', 'label_war', 'icon', 'base_severity', 'is_life_threatening']),
            'priority_config' => PriorityConfig::load()->toArray(),
        ]);
    }
}
