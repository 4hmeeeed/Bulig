<?php

namespace Database\Seeders;

use Illuminate\Database\Seeder;

class DatabaseSeeder extends Seeder
{
    public function run(): void
    {
        $this->call([
            EmergencyTypeSeeder::class,
            SettingSeeder::class,
            DemoDataSeeder::class,
        ]);
    }
}
