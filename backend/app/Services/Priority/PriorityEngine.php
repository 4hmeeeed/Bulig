<?php

namespace App\Services\Priority;

use App\Models\Emergency;
use App\Models\EmergencyType;
use Carbon\CarbonInterface;

/**
 * Explainable, deterministic incident prioritisation.
 *
 * Deliberately rule-based rather than learned: a barangay operator must be able
 * to see why an incident outranks another, and a panel must be able to audit the
 * decision. Every contribution is recorded in the returned trace.
 *
 * The same formula is implemented in Kotlin on the device so a resident sees a
 * priority while offline. The server's result is authoritative on disagreement.
 *
 * @see docs/08-priority-engine.md
 */
class PriorityEngine
{
    public function __construct(private readonly ?PriorityConfig $config = null) {}

    private function config(): PriorityConfig
    {
        return $this->config ?? PriorityConfig::load();
    }

    public function scoreEmergency(Emergency $emergency, ?CarbonInterface $now = null): PriorityResult
    {
        return $this->score(
            type: $emergency->type ?? $emergency->type()->first(),
            affectedCount: $emergency->affected_count,
            childrenCount: $emergency->children_count,
            elderlyCount: $emergency->elderly_count,
            mobilityLimitedCount: $emergency->mobility_limited_count,
            isLifeThreatening: $emergency->is_life_threatening,
            reportedAt: $emergency->created_at_device ?? $emergency->received_at_server,
            firstHopCount: $emergency->first_hop_count,
            status: $emergency->status,
            now: $now,
        );
    }

    public function score(
        EmergencyType $type,
        int $affectedCount = 1,
        int $childrenCount = 0,
        int $elderlyCount = 0,
        int $mobilityLimitedCount = 0,
        bool $isLifeThreatening = false,
        ?CarbonInterface $reportedAt = null,
        int $firstHopCount = 0,
        string $status = 'NEW',
        ?CarbonInterface $now = null,
    ): PriorityResult {
        $cfg = $this->config();
        $w = $cfg->weights;
        $now ??= now();
        $factors = [];

        $score = $type->base_severity;
        $factors[] = $this->factor('base_severity', $type->code, $type->base_severity);

        if ($isLifeThreatening) {
            $score += $w['life_threatening'];
            $factors[] = $this->factor('life_threatening', 'reporter asserted', $w['life_threatening']);
        }

        $affectedPoints = $this->bandPoints($affectedCount, $w['affected_bands']);
        $score += $affectedPoints;
        $factors[] = $this->factor(
            'affected_count', $this->persons($affectedCount), $affectedPoints
        );

        $score += $this->cappedContribution(
            $childrenCount, $w['per_child'], $w['child_cap'], 'children', $factors
        );
        $score += $this->cappedContribution(
            $elderlyCount, $w['per_elderly'], $w['elderly_cap'], 'elderly', $factors
        );
        $score += $this->cappedContribution(
            $mobilityLimitedCount, $w['per_mobility_limited'], $w['mobility_cap'],
            'mobility_limited', $factors
        );

        // An unresolved report becomes more urgent simply by going unanswered.
        $agePoints = 0;
        $ageHours = 0.0;
        if ($reportedAt && ! in_array($status, Emergency::TERMINAL_STATUSES, true)) {
            $ageHours = max(0, $reportedAt->diffInMinutes($now) / 60);
            $agePoints = (int) min($w['age_cap'], floor($ageHours) * $w['per_hour_age']);
            if ($agePoints > 0) {
                $score += $agePoints;
                $factors[] = $this->factor(
                    'report_age', sprintf('%.1f h unresolved', $ageHours), $agePoints
                );
            }
        }

        // A report that needed several hops to escape implies a connectivity dead
        // zone, which makes reaching the people involved harder.
        if ($firstHopCount >= $w['multi_hop_min_hops']) {
            $score += $w['multi_hop_arrival'];
            $factors[] = $this->factor(
                'multi_hop_arrival', "arrived at hop {$firstHopCount}", $w['multi_hop_arrival']
            );
        }

        $score = (int) max(0, min($w['score_max'], $score));
        $level = $this->band($score, $cfg->bands);

        [$level, $escalations] = $this->applyEscalations(
            $level, $cfg, $type, $isLifeThreatening, $mobilityLimitedCount, $status, $ageHours
        );

        return new PriorityResult(
            score: $score,
            level: $level,
            factors: $factors,
            escalations: $escalations,
            configVersion: $cfg->version,
        );
    }

    /**
     * Escalations run after banding so they can express floors that a raw score
     * might miss — e.g. a life-threatening report must never read as MODERATE.
     */
    private function applyEscalations(
        string $level,
        PriorityConfig $cfg,
        EmergencyType $type,
        bool $isLifeThreatening,
        int $mobilityLimitedCount,
        string $status,
        float $ageHours,
    ): array {
        $escalations = [];
        $order = array_column($cfg->bands, 'level');

        $raiseTo = function (string $target) use (&$level, $order) {
            if (array_search($target, $order, true) > array_search($level, $order, true)) {
                $level = $target;

                return true;
            }

            return false;
        };

        if (($cfg->escalations['life_threatening_min_high'] ?? false)
            && ($isLifeThreatening || $type->is_life_threatening)) {
            $escalations[] = [
                'rule' => 'life_threatening_min_high',
                'applied' => $raiseTo('HIGH'),
                'note' => 'life-threatening reports are at least HIGH',
            ];
        }

        if (($cfg->escalations['mobility_life_threat_critical'] ?? false)
            && $isLifeThreatening && $mobilityLimitedCount > 0) {
            $escalations[] = [
                'rule' => 'mobility_life_threat_critical',
                'applied' => $raiseTo('CRITICAL'),
                'note' => 'life threat with mobility-limited persons present',
            ];
        }

        // A report nobody has triaged is itself a problem worth surfacing.
        $staleHours = $cfg->escalations['stale_new_raises_band_hours'] ?? null;
        if ($staleHours && $status === 'NEW' && $ageHours > $staleHours) {
            $idx = array_search($level, $order, true);
            $applied = $idx !== false && $idx < count($order) - 1;
            if ($applied) {
                $level = $order[$idx + 1];
            }
            $escalations[] = [
                'rule' => 'stale_new_raises_band',
                'applied' => $applied,
                'note' => sprintf('untriaged for %.1f h', $ageHours),
            ];
        }

        return [$level, $escalations];
    }

    private function cappedContribution(
        int $count, int $per, int $cap, string $rule, array &$factors
    ): int {
        if ($count <= 0) {
            return 0;
        }

        $points = (int) min($cap, $count * $per);
        $factors[] = $this->factor($rule, $this->persons($count), $points);

        return $points;
    }

    private function bandPoints(int $value, array $bands): int
    {
        foreach ($bands as $band) {
            if ($value <= $band['max']) {
                return (int) $band['points'];
            }
        }

        return 0;
    }

    private function band(int $score, array $bands): string
    {
        foreach ($bands as $band) {
            if ($score <= $band['max']) {
                return $band['level'];
            }
        }

        return 'LOW';
    }

    private function persons(int $count): string
    {
        return $count.' '.($count === 1 ? 'person' : 'persons');
    }

    private function factor(string $rule, string $detail, int $points): array
    {
        return ['rule' => $rule, 'detail' => $detail, 'points' => $points];
    }
}
