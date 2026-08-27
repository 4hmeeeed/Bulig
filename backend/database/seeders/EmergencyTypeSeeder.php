<?php

namespace Database\Seeders;

use App\Models\EmergencyType;
use Illuminate\Database\Seeder;

class EmergencyTypeSeeder extends Seeder
{
    public function run(): void
    {
        // Configurable per section 8 — seeded, not hardcoded into the app.
        // Waray-Waray labels sit alongside English for resident-facing screens.
        $types = [
            ['MEDICAL', 'Medical Emergency', 'Emerhensya Medikal', 'heart-pulse', 35, true],
            ['FIRE', 'Fire', 'Sunog', 'flame', 40, true],
            ['TRAPPED', 'Trapped Person', 'Nasakop nga Tawo', 'user-lock', 40, true],
            ['LANDSLIDE', 'Landslide', 'Pagkatumba han Yuta', 'mountain', 35, true],
            ['EARTHQUAKE', 'Earthquake', 'Linog', 'waves', 35, true],
            ['FLOOD', 'Flood', 'Baha', 'droplets', 30, false],
            ['RESCUE', 'Rescue Needed', 'Kinahanglan Bulig', 'life-buoy', 30, false],
            ['MISSING', 'Missing Person', 'Nawara nga Tawo', 'user-search', 25, false],
            ['INFRA', 'Infrastructure Damage', 'Nadaot nga Pasilidad', 'construction', 15, false],
            ['OTHER', 'Other', 'Iba pa', 'circle-help', 10, false],
        ];

        foreach ($types as $i => [$code, $en, $war, $icon, $severity, $lifeThreatening]) {
            EmergencyType::updateOrCreate(['code' => $code], [
                'label_en' => $en,
                'label_war' => $war,
                'icon' => $icon,
                'base_severity' => $severity,
                'is_life_threatening' => $lifeThreatening,
                'sort_order' => $i,
                'is_active' => true,
            ]);
        }
    }
}
