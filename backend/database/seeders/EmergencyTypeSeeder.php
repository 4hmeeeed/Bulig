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
        // Labels and Waray-Waray strings are taken verbatim from the design
        // handoff (docs/design/README.md), which is authoritative for copy.
        //
        // TO BE VALIDATED: several of these strings are flagged in the handoff
        // as "reviewed placeholders". At least three read as Cebuano or
        // Hiligaynon rather than Waray-Waray — see docs/design/COPY-REVIEW.md.
        // They must be checked by a native Waray speaker before the pilot.
        $types = [
            ['MEDICAL', 'Medical', 'Emerhensya Medikal', 'medical_services', 35, true],
            ['FIRE', 'Fire', 'Sunog', 'local_fire_department', 40, true],
            ['FLOOD', 'Flood', 'Baha', 'flood', 30, false],
            ['LANDSLIDE', 'Landslide', 'Pagdahili sang tuna', 'landslide', 35, true],
            ['EARTHQUAKE', 'Earthquake', 'Linog', 'earthquake', 35, true],
            ['RESCUE', 'Rescue needed', 'Kinahanglan bulig', 'hail', 30, false],
            ['MISSING', 'Missing person', 'Nawawara nga tawo', 'person_search', 25, false],
            ['TRAPPED', 'Trapped person', 'Nakukulong nga tawo', 'emergency_home', 40, true],
            ['INFRA', 'Infrastructure', 'Nadaot nga pasilidad', 'construction', 15, false],
            ['OTHER', 'Other', 'Iba pa', 'more_horiz', 10, false],
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
