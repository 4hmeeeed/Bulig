<?php

namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Response;

class EnsureRole
{
    public function handle(Request $request, Closure $next, string ...$roles): Response
    {
        $user = $request->user();

        if (! $user || ! $user->is_active || ! $user->hasRole(...$roles)) {
            return response()->json(
                ['message' => 'This action is unauthorized.', 'code' => 'FORBIDDEN_ROLE'],
                403
            );
        }

        return $next($request);
    }
}
