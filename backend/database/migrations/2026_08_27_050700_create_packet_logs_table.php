<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        // Append-only event stream. This table IS the evaluation dataset for the
        // duplicate-suppression and TTL-enforcement metrics in docs/10-testing-plan.md.
        Schema::create('packet_logs', function (Blueprint $table) {
            $table->id();
            $table->uuid('packet_id')->index();
            $table->foreignId('sync_log_id')->nullable()->constrained('sync_logs')->nullOnDelete();
            $table->foreignId('device_id')->nullable()->constrained('devices')->nullOnDelete();
            $table->enum('event', [
                'CREATED', 'RELAY_SENT', 'RELAY_RECEIVED', 'DUPLICATE_SUPPRESSED',
                'TTL_EXPIRED', 'SYNC_ATTEMPTED', 'SYNC_ACCEPTED', 'SYNC_REJECTED',
                'INVALID_HMAC',
            ])->index();
            $table->unsignedTinyInteger('hop_count')->nullable();
            $table->unsignedTinyInteger('ttl_remaining')->nullable();
            $table->json('detail')->nullable();
            $table->timestamp('occurred_at');
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('packet_logs');
    }
};
