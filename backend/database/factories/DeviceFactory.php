<?php

namespace Database\Factories;

use Illuminate\Database\Eloquent\Factories\Factory;
use Illuminate\Support\Str;

class DeviceFactory extends Factory
{
    public function definition(): array
    {
        return [
            'device_id' => Str::uuid()->toString(),
            'label' => 'Test relay '.fake()->numberBetween(1, 99),
            'model' => 'Redmi 9A',
            'android_version' => '11',
            'hmac_key' => random_bytes(32),
            'supports_advertising' => true,
            'is_revoked' => false,
        ];
    }

    public function revoked(): static
    {
        return $this->state(['is_revoked' => true]);
    }

    /** A device that has never reached the server, so its packets cannot be verified. */
    public function unregisteredKey(): static
    {
        return $this->state(['hmac_key' => null]);
    }
}
