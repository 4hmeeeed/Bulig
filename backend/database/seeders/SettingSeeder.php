<?php

namespace Database\Seeders;

use App\Models\Setting;
use App\Services\Priority\PriorityConfig;
use Illuminate\Database\Seeder;

class SettingSeeder extends Seeder
{
    public function run(): void
    {
        $settings = [
            [PriorityConfig::SETTING_KEY, PriorityConfig::default()->toArray(), 'priority',
                'Scoring weights, bands and escalation rules for incident prioritisation.'],
            ['mesh_ttl_initial', 10, 'mesh',
                'Hops a packet may travel before it stops being forwarded.'],
            ['mesh_battery_floor_pct', 15, 'mesh',
                'Relaying pauses below this battery level.'],
            ['mesh_max_packet_age_hours', 24, 'mesh',
                'Packets older than this are no longer forwarded.'],
            ['sync_batch_size', 50, 'sync',
                'Maximum packets per synchronisation request.'],
            ['map_default_center', ['lat' => 11.2444, 'lng' => 125.0048], 'map',
                'Initial command center map centre. TO BE VALIDATED with the selected barangay.'],
            ['map_default_zoom', 15, 'map', 'Initial command center map zoom level.'],
        ];

        foreach ($settings as [$key, $value, $group, $description]) {
            Setting::updateOrCreate(['key' => $key], [
                'value' => $value,
                'group' => $group,
                'description' => $description,
            ]);
        }
    }
}
