<?php

namespace App\Http\Requests\Api;

use App\Models\Setting;
use Illuminate\Foundation\Http\FormRequest;

class SyncPacketsRequest extends FormRequest
{
    public function authorize(): bool
    {
        return true;
    }

    public function rules(): array
    {
        $max = (int) Setting::get('sync_batch_size', 50);

        return [
            'client_clock' => ['required', 'date'],
            'packets' => ['required', 'array', 'min:1', "max:{$max}"],
            'packets.*.packet_id' => ['required', 'uuid'],
            'packets.*.emergency_id' => ['required', 'uuid'],
            'packets.*.origin_device_id' => ['required', 'uuid'],
            'packets.*.hop_count' => ['required', 'integer', 'min:0', 'max:255'],
            'packets.*.ttl_remaining' => ['required', 'integer', 'min:0', 'max:255'],
            'packets.*.ttl_initial' => ['required', 'integer', 'min:1', 'max:255'],
            'packets.*.created_at_device' => ['required', 'date'],
            'packets.*.hmac' => ['nullable', 'string', 'max:64'],
            'packets.*.route_path' => ['nullable', 'array', 'max:32'],
            'packets.*.route_path.*' => ['uuid'],

            'packets.*.payload' => ['required', 'array'],
            'packets.*.payload.type_code' => ['required', 'string', 'max:32'],
            'packets.*.payload.description' => ['nullable', 'string', 'max:1000'],
            'packets.*.payload.affected_count' => ['required', 'integer', 'min:0', 'max:9999'],
            'packets.*.payload.children_count' => ['nullable', 'integer', 'min:0', 'max:9999'],
            'packets.*.payload.elderly_count' => ['nullable', 'integer', 'min:0', 'max:9999'],
            'packets.*.payload.mobility_limited_count' => ['nullable', 'integer', 'min:0', 'max:9999'],
            'packets.*.payload.is_life_threatening' => ['nullable', 'boolean'],
            'packets.*.payload.vulnerability_notes' => ['nullable', 'string', 'max:255'],
            'packets.*.payload.latitude' => ['nullable', 'numeric', 'between:-90,90'],
            'packets.*.payload.longitude' => ['nullable', 'numeric', 'between:-180,180'],
            'packets.*.payload.accuracy_m' => ['nullable', 'numeric', 'min:0'],
            'packets.*.payload.location_provider' => ['nullable', 'in:gps,network,manual'],
            'packets.*.payload.captured_at' => ['nullable', 'date'],
        ];
    }
}
