<?php

namespace App\Http\Middleware;

use App\Models\Device;
use Closure;
use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Response;

/**
 * Requires that the bearer token belongs to a registered device, not a person.
 *
 * Device tokens are scoped to synchronisation only. A relay phone belongs to an
 * ordinary resident, so a compromised device token must not expose the barangay's
 * incident list — hence a separate tokenable rather than a user session.
 */
class EnsureDeviceToken
{
    public function handle(Request $request, Closure $next): Response
    {
        $device = $request->user();

        if (! $device instanceof Device) {
            return response()->json(
                ['message' => 'A device token is required.', 'code' => 'DEVICE_TOKEN_REQUIRED'],
                403
            );
        }

        if ($device->is_revoked) {
            return response()->json(
                ['message' => 'Device is revoked.', 'code' => 'DEVICE_REVOKED'],
                403
            );
        }

        return $next($request);
    }
}
