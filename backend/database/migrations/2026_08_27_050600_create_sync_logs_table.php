<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('sync_logs', function (Blueprint $table) {
            $table->id();
            $table->foreignId('device_id')->constrained('devices');
            $table->enum('direction', ['push', 'pull'])->default('push');
            $table->unsignedSmallInteger('packets_sent')->default(0);
            $table->unsignedSmallInteger('packets_accepted')->default(0);
            $table->unsignedSmallInteger('packets_duplicate')->default(0);
            $table->unsignedSmallInteger('packets_rejected')->default(0);
            $table->unsignedInteger('bytes')->default(0);
            $table->timestamp('started_at')->nullable();
            $table->timestamp('completed_at')->nullable();
            $table->unsignedInteger('duration_ms')->nullable();
            $table->enum('outcome', ['success', 'partial', 'failed'])->default('success')->index();
            $table->text('error')->nullable();
            $table->string('ip_address', 45)->nullable();
            $table->timestamp('client_clock_at_start')->nullable();
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('sync_logs');
    }
};
