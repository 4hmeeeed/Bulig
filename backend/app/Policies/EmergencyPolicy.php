<?php

namespace App\Policies;

use App\Models\Emergency;
use App\Models\User;

class EmergencyPolicy
{
    public function viewAny(User $user): bool
    {
        return $user->hasRole('operator', 'official', 'sysadmin', 'responder');
    }

    /**
     * A responder sees only what they were sent to. This is the primary privacy
     * boundary in the system: emergency reports name vulnerable people, and a
     * responder has no reason to read incidents outside their own assignments.
     */
    public function view(User $user, Emergency $emergency): bool
    {
        if ($user->isStaff()) {
            return true;
        }

        if ($user->hasRole('responder')) {
            return $this->isAssignedTo($user, $emergency);
        }

        return $emergency->reported_by_user_id === $user->id;
    }

    public function updateStatus(User $user, Emergency $emergency): bool
    {
        if ($user->hasRole('operator', 'official')) {
            return true;
        }

        return $user->hasRole('responder') && $this->isAssignedTo($user, $emergency);
    }

    private function isAssignedTo(User $user, Emergency $emergency): bool
    {
        $responder = $user->responder;

        if (! $responder) {
            return false;
        }

        return $emergency->assignments()
            ->where(function ($q) use ($responder) {
                $q->where('responder_id', $responder->id)
                    ->orWhere(fn ($t) => $t->whereNotNull('rescue_team_id')
                        ->where('rescue_team_id', $responder->rescue_team_id));
            })
            ->exists();
    }

    public function assign(User $user): bool
    {
        return $user->hasRole('operator', 'official');
    }

    /**
     * An operator may raise priority but not lower it; lowering needs an official.
     * Escalating under pressure is a judgement call, but quietly de-prioritising
     * someone's emergency should require more authority and always leaves a reason.
     */
    public function overridePriority(User $user, Emergency $emergency, string $newLevel): bool
    {
        $order = ['LOW' => 0, 'MODERATE' => 1, 'HIGH' => 2, 'CRITICAL' => 3];
        $isRaise = ($order[$newLevel] ?? 0) > ($order[$emergency->priority_level] ?? 0);

        if ($user->hasRole('official', 'sysadmin')) {
            return true;
        }

        return $user->hasRole('operator') && $isRaise;
    }
}
