<?php

namespace Tests\Unit;

use App\Models\EmergencyType;
use App\Services\Priority\PriorityConfig;
use App\Services\Priority\PriorityEngine;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

/**
 * The worked examples in docs/08-priority-engine.md are the fixtures here, so
 * the documentation and the implementation cannot drift apart silently.
 */
class PriorityEngineTest extends TestCase
{
    use RefreshDatabase;

    private PriorityEngine $engine;

    protected function setUp(): void
    {
        parent::setUp();
        $this->seed(\Database\Seeders\EmergencyTypeSeeder::class);
        $this->engine = new PriorityEngine(PriorityConfig::default());
    }

    private function type(string $code): EmergencyType
    {
        return EmergencyType::where('code', $code)->firstOrFail();
    }

    /** Example A: elderly cardiac emergency, 4 affected, arrived at hop 3. */
    public function test_example_a_scores_81_critical(): void
    {
        $result = $this->engine->score(
            type: $this->type('MEDICAL'),
            affectedCount: 4,
            elderlyCount: 2,
            mobilityLimitedCount: 1,
            isLifeThreatening: true,
            reportedAt: now()->subHour(),
            firstHopCount: 3,
        );

        $this->assertSame(81, $result->score);
        $this->assertSame('CRITICAL', $result->level);
    }

    /** Example B: minor flooding, 3 affected, no vulnerable persons, fresh. */
    public function test_example_b_scores_35_moderate(): void
    {
        $result = $this->engine->score(
            type: $this->type('FLOOD'),
            affectedCount: 3,
            reportedAt: now(),
        );

        $this->assertSame(35, $result->score);
        $this->assertSame('MODERATE', $result->level);
    }

    /** Example C: reported road damage, 1 affected, fresh. */
    public function test_example_c_scores_15_low(): void
    {
        $result = $this->engine->score(
            type: $this->type('INFRA'),
            affectedCount: 1,
            reportedAt: now(),
        );

        $this->assertSame(15, $result->score);
        $this->assertSame('LOW', $result->level);
    }

    /** Example D: house fire, 12 affected incl. 3 children and 2 elderly, 2 h old. */
    public function test_example_d_clamps_at_100_critical(): void
    {
        $result = $this->engine->score(
            type: $this->type('FIRE'),
            affectedCount: 12,
            childrenCount: 3,
            elderlyCount: 2,
            isLifeThreatening: true,
            reportedAt: now()->subHours(2),
        );

        $this->assertSame(100, $result->score);
        $this->assertSame('CRITICAL', $result->level);
    }

    public function test_breakdown_records_every_contributing_rule(): void
    {
        $result = $this->engine->score(
            type: $this->type('MEDICAL'),
            affectedCount: 4,
            elderlyCount: 2,
            mobilityLimitedCount: 1,
            isLifeThreatening: true,
            reportedAt: now()->subHour(),
            firstHopCount: 3,
        );

        $rules = array_column($result->factors, 'rule');

        $this->assertEqualsCanonicalizing([
            'base_severity', 'life_threatening', 'affected_count',
            'elderly', 'mobility_limited', 'report_age', 'multi_hop_arrival',
        ], $rules);

        // The trace must add up to the score, or the explanation is a fiction.
        $this->assertSame($result->score, array_sum(array_column($result->factors, 'points')));
    }

    /** A life-threatening report must never read as MODERATE, whatever it scores. */
    public function test_life_threatening_report_is_escalated_to_at_least_high(): void
    {
        $result = $this->engine->score(
            type: $this->type('MISSING'),
            affectedCount: 1,
            isLifeThreatening: true,
            reportedAt: now(),
        );

        $this->assertSame(50, $result->score);
        $this->assertSame('HIGH', $result->level);
    }

    public function test_life_threat_with_mobility_limited_person_is_critical(): void
    {
        $result = $this->engine->score(
            type: $this->type('OTHER'),
            affectedCount: 1,
            mobilityLimitedCount: 1,
            isLifeThreatening: true,
            reportedAt: now(),
        );

        $this->assertSame('CRITICAL', $result->level);
    }

    /**
     * A report nobody has triaged is itself a problem.
     *
     * Age acts twice by design: it adds points (capped), and once a report has
     * gone untriaged past the configured threshold it also raises the band. An
     * 8-hour-old INFRA report therefore lands at 25 (MODERATE by score) and is
     * then escalated to HIGH.
     */
    public function test_stale_untriaged_report_gains_points_and_a_band(): void
    {
        $fresh = $this->engine->score(
            type: $this->type('INFRA'), affectedCount: 1, reportedAt: now(), status: 'NEW'
        );
        $stale = $this->engine->score(
            type: $this->type('INFRA'), affectedCount: 1,
            reportedAt: now()->subHours(8), status: 'NEW'
        );

        $this->assertSame(15, $fresh->score);
        $this->assertSame('LOW', $fresh->level);

        $this->assertSame(25, $stale->score);
        $this->assertSame('HIGH', $stale->level);

        $escalation = collect($stale->escalations)->firstWhere('rule', 'stale_new_raises_band');
        $this->assertTrue($escalation['applied']);
    }

    /** A report still inside the threshold gains points but keeps its band. */
    public function test_recent_untriaged_report_is_not_escalated(): void
    {
        $result = $this->engine->score(
            type: $this->type('INFRA'), affectedCount: 1,
            reportedAt: now()->subHours(3), status: 'NEW'
        );

        $this->assertSame(21, $result->score);
        $this->assertSame('LOW', $result->level);
        $this->assertEmpty(array_filter(
            $result->escalations, fn ($e) => $e['rule'] === 'stale_new_raises_band'
        ));
    }

    /** Ageing must not keep pushing a closed incident up the queue. */
    public function test_resolved_incidents_do_not_accrue_age_points(): void
    {
        $result = $this->engine->score(
            type: $this->type('INFRA'),
            affectedCount: 1,
            reportedAt: now()->subDays(3),
            status: 'RESOLVED',
        );

        $this->assertSame(15, $result->score);
        $this->assertEmpty(array_filter(
            $result->factors, fn ($f) => $f['rule'] === 'report_age'
        ));
    }

    public function test_scoring_is_deterministic(): void
    {
        $args = [
            'type' => $this->type('FIRE'),
            'affectedCount' => 7,
            'childrenCount' => 1,
            'reportedAt' => now()->subMinutes(90),
        ];

        $a = $this->engine->score(...$args);
        $b = $this->engine->score(...$args);

        $this->assertSame($a->score, $b->score);
        $this->assertSame($a->factors, $b->factors);
    }
}
