<?php

namespace App\Policies;

use App\Models\RescueAssignment;
use App\Models\User;

class RescueAssignmentPolicy
{
    public function create(User $user): bool
    {
        return $user->hasRole('operator', 'official');
    }

    public function act(User $user, RescueAssignment $assignment): bool
    {
        return $user->hasRole('responder')
            && $assignment->responder?->user_id === $user->id;
    }
}
