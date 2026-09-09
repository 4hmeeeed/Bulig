<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('emergency_locations', function (Blueprint $table) {
            $table->id();
            $table->foreignId('emergency_id')->unique()->constrained()->cascadeOnDelete();
            $table->decimal('latitude', 10, 7);
            $table->decimal('longitude', 10, 7);
            $table->float('accuracy_m')->nullable();
            $table->enum('provider', ['gps', 'network', 'manual'])->default('gps');
            $table->boolean('is_approximate')->default(false);
            $table->timestamp('captured_at')->nullable();
            $table->timestamps();

            $table->index(['latitude', 'longitude']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('emergency_locations');
    }
};
