<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        // Append-only. Drives the incident timeline in the command center.
        Schema::create('status_history', function (Blueprint $table) {
            $table->id();
            $table->foreignId('emergency_id')->constrained()->cascadeOnDelete();
            $table->string('from_status', 20)->nullable();
            $table->string('to_status', 20);
            $table->foreignId('changed_by_user_id')->nullable()->constrained('users')->nullOnDelete();
            $table->enum('source', ['system', 'operator', 'responder', 'sync'])->default('system');
            $table->string('note', 255)->nullable();
            $table->timestamp('occurred_at');
            $table->timestamps();

            $table->index(['emergency_id', 'occurred_at']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('status_history');
    }
};
