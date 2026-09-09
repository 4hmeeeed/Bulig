<?php

namespace App\Services;

use App\Models\Emergency;
use App\Models\EmergencyPacket;
use App\Models\PacketLog;
use App\Models\SyncLog;
use Illuminate\Support\Collection;

/**
 * Computes the operational metrics the evaluation chapter reports.
 *
 * The system records its own evaluation data as it runs, so these figures come
 * from queries rather than from stopwatches and spreadsheets the week before
 * defense.
 *
 * @see docs/10-testing-plan.md 10.5
 */
class EvaluationMetricsService
{
    public function all(): array
    {
        return [
            'delivery' => $this->deliveryByHopCount(),
            'transmission_delay_ms' => $this->transmissionDelay(),
            'duplicate_suppression' => $this->duplicateSuppression(),
            'ttl_enforcement' => $this->ttlEnforcement(),
            'synchronisation' => $this->synchronisation(),
            'integrity' => $this->integrity(),
            'relay_contribution' => $this->relayContribution(),
            'generated_at' => now()->toIso8601String(),
        ];
    }

    /** Metrics 3 and 4: one-hop and multi-hop delivery. */
    public function deliveryByHopCount(): array
    {
        $byHop = EmergencyPacket::query()
            ->selectRaw('hop_count, COUNT(*) as total')
            ->groupBy('hop_count')
            ->orderBy('hop_count')
            ->pluck('total', 'hop_count');

        $delivered = EmergencyPacket::whereIn('status', ['ACCEPTED', 'TTL_EXPIRED'])->count();
        $total = EmergencyPacket::count();

        return [
            'by_hop_count' => $byHop,
            'direct_hop_0' => (int) ($byHop[0] ?? 0),
            'one_hop' => (int) ($byHop[1] ?? 0),
            'multi_hop' => (int) $byHop->filter(fn ($v, $k) => $k >= 2)->sum(),
            'delivered' => $delivered,
            'total_packets' => $total,
            'delivery_rate' => $total > 0 ? round($delivered / $total, 4) : null,
        ];
    }

    /**
     * Metric 5. Delays are corrected for device clock drift; packets whose
     * corrected delay is still impossible are excluded rather than averaged in,
     * and reported separately so the exclusion is visible.
     */
    public function transmissionDelay(): array
    {
        $packets = EmergencyPacket::whereNotNull('created_at_device')
            ->whereNotNull('received_at_server')
            ->get(['created_at_device', 'received_at_server', 'clock_offset_ms', 'hop_count']);

        $delays = $packets
            ->map(fn (EmergencyPacket $p) => $p->correctedDelayMs())
            ->filter(fn (?int $d) => $d !== null && $d >= 0)
            ->sort()
            ->values();

        return [
            'n' => $delays->count(),
            'excluded_clock_anomaly' => $packets->count() - $delays->count(),
            'min' => $delays->first(),
            'median' => $this->percentile($delays, 0.50),
            'p90' => $this->percentile($delays, 0.90),
            'max' => $delays->last(),
            'mean' => $delays->isEmpty() ? null : (int) round($delays->avg()),
        ];
    }

    /** Metric 6. */
    public function duplicateSuppression(): array
    {
        $suppressed = PacketLog::where('event', 'DUPLICATE_SUPPRESSED')->count();
        $received = PacketLog::whereIn('event', ['RELAY_RECEIVED', 'SYNC_ACCEPTED'])->count();

        return [
            'suppressed' => $suppressed,
            'received' => $received,
            'suppression_rate' => ($received + $suppressed) > 0
                ? round($suppressed / ($received + $suppressed), 4)
                : null,
            // The property that matters: duplicates never became extra incidents.
            'distinct_emergencies' => Emergency::count(),
            'distinct_packets' => EmergencyPacket::count(),
        ];
    }

    /** Metric 7. */
    public function ttlEnforcement(): array
    {
        return [
            'ttl_expired_packets' => EmergencyPacket::where('status', 'TTL_EXPIRED')->count(),
            'ttl_expired_events' => PacketLog::where('event', 'TTL_EXPIRED')->count(),
            // Must be zero: a packet cannot have taken more hops than its TTL allowed.
            'ttl_violations' => EmergencyPacket::whereColumn('hop_count', '>', 'ttl_initial')->count(),
        ];
    }

    /** Metric 8. */
    public function synchronisation(): array
    {
        $outcomes = SyncLog::selectRaw('outcome, COUNT(*) as total')
            ->groupBy('outcome')->pluck('total', 'outcome');
        $total = $outcomes->sum();

        return [
            'by_outcome' => $outcomes,
            'total_batches' => $total,
            'success_rate' => $total > 0 ? round(($outcomes['success'] ?? 0) / $total, 4) : null,
            'mean_duration_ms' => SyncLog::whereNotNull('duration_ms')->avg('duration_ms'),
            'packets_accepted' => SyncLog::sum('packets_accepted'),
            'packets_rejected' => SyncLog::sum('packets_rejected'),
        ];
    }

    public function integrity(): array
    {
        return [
            'hmac_verified' => EmergencyPacket::where('hmac_valid', true)->count(),
            'hmac_failed' => EmergencyPacket::where('hmac_valid', false)->count(),
            'hmac_unverifiable' => EmergencyPacket::whereNull('hmac_valid')->count(),
        ];
    }

    /** Which devices actually carried other people's reports. */
    public function relayContribution(): Collection
    {
        return PacketLog::query()
            ->selectRaw('device_id, COUNT(*) as events')
            ->whereIn('event', ['RELAY_RECEIVED', 'RELAY_SENT'])
            ->whereNotNull('device_id')
            ->groupBy('device_id')
            ->orderByDesc('events')
            ->with('device:id,device_id,label')
            ->limit(20)
            ->get();
    }

    private function percentile(Collection $sorted, float $p): ?int
    {
        if ($sorted->isEmpty()) {
            return null;
        }

        $index = (int) floor($p * ($sorted->count() - 1));

        return (int) $sorted[$index];
    }
}
