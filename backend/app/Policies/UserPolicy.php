<?php

namespace App\Policies;

use App\Models\User;

class UserPolicy
{
    public function viewAny(User $user): bool
    {
        return $user->hasRole('official', 'sysadmin');
    }

    public function create(User $user): bool
    {
        return $user->hasRole('official', 'sysadmin');
    }

    /** An official manages barangay staff, but cannot touch a system administrator. */
    public function update(User $user, User $target): bool
    {
        if ($user->hasRole('sysadmin')) {
            return true;
        }

        return $user->hasRole('official') && ! $target->hasRole('sysadmin');
    }

    public function delete(User $user, User $target): bool
    {
        return $user->id !== $target->id && $this->update($user, $target);
    }
}
