<?php

namespace App\Services\Priority;

use App\Models\Setting;

/**
 * Versioned, configurable scoring configuration.
 *
 * Lives in `settings.priority_config` so weights and bands can be tuned without
 * a deploy (section 9 of the proposal). Historical breakdowns record the version
 * they were computed under, so a past decision stays readable in the terms that
 * produced it. Mirrored by the Kotlin implementation on the device.
 *
 * @see docs/08-priority-engine.md
 */
final class PriorityConfig
{
    public const SETTING_KEY = 'priority_config';

    public function __construct(
        public readonly int $version,
        public readonly array $weights,
        public readonly array $bands,
        public readonly array $escalations,
    ) {}

    public static function default(): self
    {
        return new self(
            version: 1,
            weights: [
                'life_threatening' => 25,
                'affected_bands' => [
                    ['max' => 1, 'points' => 0],
                    ['max' => 5, 'points' => 5],
                    ['max' => 10, 'points' => 10],
                    ['max' => 25, 'points' => 15],
                    ['max' => PHP_INT_MAX, 'points' => 20],
                ],
                'per_child' => 4,
                'child_cap' => 12,
                'per_elderly' => 3,
                'elderly_cap' => 9,
                'per_mobility_limited' => 5,
                'mobility_cap' => 15,
                'per_hour_age' => 2,
                'age_cap' => 10,
                'multi_hop_arrival' => 3,
                'multi_hop_min_hops' => 2,
                'score_max' => 100,
            ],
            bands: [
                ['max' => 24, 'level' => 'LOW'],
                ['max' => 44, 'level' => 'MODERATE'],
                ['max' => 69, 'level' => 'HIGH'],
                ['max' => PHP_INT_MAX, 'level' => 'CRITICAL'],
            ],
            escalations: [
                'life_threatening_min_high' => true,
                'mobility_life_threat_critical' => true,
                'stale_new_raises_band_hours' => 6,
            ],
        );
    }

    public static function load(): self
    {
        $stored = Setting::get(self::SETTING_KEY);

        if (! is_array($stored)) {
            return self::default();
        }

        $default = self::default();

        return new self(
            version: $stored['version'] ?? $default->version,
            weights: array_replace($default->weights, $stored['weights'] ?? []),
            bands: $stored['bands'] ?? $default->bands,
            escalations: array_replace($default->escalations, $stored['escalations'] ?? []),
        );
    }

    public function toArray(): array
    {
        return [
            'version' => $this->version,
            'weights' => $this->weights,
            'bands' => $this->bands,
            'escalations' => $this->escalations,
        ];
    }
}
