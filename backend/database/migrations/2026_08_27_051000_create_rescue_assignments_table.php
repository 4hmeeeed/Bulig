<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('rescue_assignments', function (Blueprint $table) {
            $table->id();
            $table->foreignId('emergency_id')->constrained()->cascadeOnDelete();
            $table->foreignId('rescue_team_id')->nullable()->constrained()->nullOnDelete();
            $table->foreignId('responder_id')->nullable()->constrained('responders')->nullOnDelete();
            $table->foreignId('assigned_by_user_id')->constrained('users');
            $table->enum('status', [
                'ASSIGNED', 'ACCEPTED', 'EN_ROUTE', 'ON_SITE',
                'RESOLVED', 'DECLINED', 'REASSIGNED',
            ])->default('ASSIGNED')->index();
            $table->timestamp('assigned_at');
            $table->timestamp('accepted_at')->nullable();
            $table->timestamp('en_route_at')->nullable();
            $table->timestamp('on_site_at')->nullable();
            $table->timestamp('resolved_at')->nullable();
            $table->string('decline_reason', 255)->nullable();
            $table->text('notes')->nullable();
            $table->unsignedSmallInteger('persons_assisted')->nullable();
            $table->timestamps();

            $table->index(['emergency_id', 'status']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('rescue_assignments');
    }
};
