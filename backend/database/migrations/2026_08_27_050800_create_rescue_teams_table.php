<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('rescue_teams', function (Blueprint $table) {
            $table->id();
            $table->string('name', 80);
            $table->string('code', 20)->unique();
            $table->string('contact_number', 20)->nullable();
            $table->string('base_location', 120)->nullable();
            $table->decimal('base_latitude', 10, 7)->nullable();
            $table->decimal('base_longitude', 10, 7)->nullable();
            $table->boolean('is_active')->default(true);
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('rescue_teams');
    }
};
