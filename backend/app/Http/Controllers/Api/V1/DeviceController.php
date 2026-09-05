<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Models\Device;
use App\Models\Setting;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

class DeviceController extends Controller
{
    /**
     * Registers a relay node and issues its signing key.
     *
     * The key is returned exactly once. Re-registering an existing device_id
     * rotates it and invalidates prior tokens, so a lost phone can be cut off.
     *
     * Note that registration is NOT a prerequisite for reporting: a device that
     * has never reached the server can still create and relay packets. Requiring
     * registration first would reintroduce the internet dependency this whole
     * architecture exists to remove — the server simply records hmac_valid as
     * null for such packets.
     */
    public function register(Request $request): JsonResponse
    {
        $data = $request->validate([
            'device_id' => ['required', 'uuid'],
            'model' => ['nullable', 'string', 'max:80'],
            'android_version' => ['nullable', 'string', 'max:20'],
            'label' => ['nullable', 'string', 'max:80'],
            'supports_advertising' => ['nullable', 'boolean'],
        ]);

        $device = Device::firstOrNew(['device_id' => $data['device_id']]);

        if ($device->exists && $device->is_revoked) {
            return response()->json(
                ['message' => 'Device is revoked.', 'code' => 'DEVICE_REVOKED'],
                403
            );
        }

        $key = random_bytes(32);
        $device->fill([
            'model' => $data['model'] ?? $device->model,
            'android_version' => $data['android_version'] ?? $device->android_version,
            'label' => $data['label'] ?? $device->label,
            'supports_advertising' => $data['supports_advertising'] ?? true,
            'hmac_key' => $key,
            'last_seen_at' => now(),
        ])->save();

        $device->tokens()->delete();

        return response()->json([
            'device_token' => $device->createToken('device', ['sync:push', 'sync:pull'])->plainTextToken,
            'hmac_key' => bin2hex($key),
            'server_time' => now()->toIso8601String(),
            'ttl_initial' => Setting::get('mesh_ttl_initial', 10),
            'sync_batch_size' => Setting::get('sync_batch_size', 50),
        ], $device->wasRecentlyCreated ? 201 : 200);
    }

    public function heartbeat(Request $request): JsonResponse
    {
        $data = $request->validate([
            'battery' => ['nullable', 'integer', 'min:0', 'max:100'],
            'has_internet' => ['nullable', 'boolean'],
        ]);

        $device = $request->user();
        $device->forceFill([
            'last_battery_pct' => $data['battery'] ?? $device->last_battery_pct,
            'last_seen_at' => now(),
        ])->save();

        return response()->json([
            'server_time' => now()->toIso8601String(),
            'ttl_initial' => Setting::get('mesh_ttl_initial', 10),
        ]);
    }
}
