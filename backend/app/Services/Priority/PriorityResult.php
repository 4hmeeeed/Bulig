<?php

namespace App\Services\Priority;

/**
 * The outcome of a scoring run, including the rule trace that produced it.
 *
 * The trace is what lets the command center answer "why is this CRITICAL?" with
 * an explanation rather than a number — the whole reason this system uses rules
 * instead of a model.
 */
final class PriorityResult
{
    public function __construct(
        public readonly int $score,
        public readonly string $level,
        public readonly array $factors,
        public readonly array $escalations,
        public readonly int $configVersion,
        public readonly string $computedBy = 'server',
    ) {}

    public function toBreakdown(): array
    {
        return [
            'config_version' => $this->configVersion,
            'score' => $this->score,
            'level' => $this->level,
            'factors' => $this->factors,
            'escalations' => $this->escalations,
            'computed_at' => now()->toIso8601String(),
            'computed_by' => $this->computedBy,
        ];
    }
}
