<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('emergencies', function (Blueprint $table) {
            $table->id();
            // Device-minted UUIDv4. The server never issues this: it cannot reach
            // an offline device to do so. Ingestion upserts on this key.
            $table->uuid('emergency_id')->unique();
            // Human-readable label assigned by the server on first sync.
            $table->string('emergency_code', 20)->nullable()->unique();
            $table->foreignId('emergency_type_id')->constrained();
            $table->text('description')->nullable();

            $table->unsignedSmallInteger('affected_count')->default(1);
            $table->unsignedSmallInteger('children_count')->default(0);
            $table->unsignedSmallInteger('elderly_count')->default(0);
            $table->unsignedSmallInteger('mobility_limited_count')->default(0);
            $table->boolean('is_life_threatening')->default(false);
            $table->string('vulnerability_notes', 255)->nullable();

            $table->enum('priority_level', ['LOW', 'MODERATE', 'HIGH', 'CRITICAL'])->default('LOW');
            $table->unsignedSmallInteger('priority_score')->default(0);
            // Explainable rule trace. The command center renders this so an
            // operator can see *why* an incident is CRITICAL.
            $table->json('priority_breakdown')->nullable();
            $table->foreignId('priority_overridden_by')->nullable()->constrained('users')->nullOnDelete();
            $table->string('priority_override_reason', 255)->nullable();

            $table->enum('status', [
                'NEW', 'TRIAGED', 'ASSIGNED', 'EN_ROUTE', 'ON_SITE',
                'RESOLVED', 'CANCELLED', 'DUPLICATE',
            ])->default('NEW');

            $table->foreignId('reported_by_user_id')->nullable()->constrained('users')->nullOnDelete();
            $table->foreignId('origin_device_id')->constrained('devices');

            // Origin clock and server clock are both retained: offline phones drift.
            $table->timestamp('created_at_device')->nullable();
            $table->timestamp('received_at_server')->nullable();
            $table->unsignedTinyInteger('first_hop_count')->default(0);
            $table->boolean('clock_anomaly')->default(false);
            $table->timestamp('resolved_at')->nullable();

            $table->timestamps();
            $table->softDeletes();

            $table->index(['status', 'priority_level']);
            $table->index('received_at_server');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('emergencies');
    }
};
