<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('emergency_types', function (Blueprint $table) {
            $table->id();
            $table->string('code', 32)->unique();
            $table->string('label_en', 80);
            $table->string('label_war', 80)->nullable();
            $table->string('icon', 40)->nullable();
            // Feeds the priority engine; see docs/08-priority-engine.md
            $table->unsignedTinyInteger('base_severity')->default(10);
            $table->boolean('is_life_threatening')->default(false);
            $table->unsignedSmallInteger('sort_order')->default(0);
            $table->boolean('is_active')->default(true);
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('emergency_types');
    }
};
