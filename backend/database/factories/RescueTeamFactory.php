<?php

namespace Database\Factories;

use Illuminate\Database\Eloquent\Factories\Factory;
use Illuminate\Support\Str;

class RescueTeamFactory extends Factory
{
    public function definition(): array
    {
        return [
            'name' => 'Team '.fake()->firstName(),
            'code' => Str::upper(Str::random(6)),
            'is_active' => true,
        ];
    }
}
