<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('devices', function (Blueprint $table) {
            $table->id();
            // Device-minted UUID. Created on first app launch, with no server involved.
            $table->uuid('device_id')->unique();
            $table->foreignId('user_id')->nullable()->constrained()->nullOnDelete();
            $table->string('label', 80)->nullable();
            $table->string('model', 80)->nullable();
            $table->string('android_version', 20)->nullable();
            // Issued once at registration; signs payloads so relays cannot tamper.
            $table->binary('hmac_key')->nullable();
            $table->boolean('supports_advertising')->default(true);
            $table->boolean('is_revoked')->default(false)->index();
            $table->unsignedTinyInteger('last_battery_pct')->nullable();
            $table->timestamp('last_seen_at')->nullable();
            $table->timestamp('last_sync_at')->nullable();
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('devices');
    }
};
