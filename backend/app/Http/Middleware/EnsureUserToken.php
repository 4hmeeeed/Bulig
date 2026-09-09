<?php

namespace App\Http\Middleware;

use App\Models\User;
use Closure;
use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Response;

/**
 * Rejects device tokens on person-scoped routes.
 *
 * Without this the request reaches a policy that expects a User and fails with a
 * type error rather than a clean refusal. A relay device must be told "no" in
 * terms its client can branch on.
 */
class EnsureUserToken
{
    public function handle(Request $request, Closure $next): Response
    {
        $user = $request->user();

        if (! $user instanceof User) {
            return response()->json(
                ['message' => 'This endpoint requires a user token.', 'code' => 'USER_TOKEN_REQUIRED'],
                403
            );
        }

        if (! $user->is_active) {
            return response()->json(
                ['message' => 'This account is disabled.', 'code' => 'ACCOUNT_DISABLED'],
                403
            );
        }

        return $next($request);
    }
}
