<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('emergency_packets', function (Blueprint $table) {
            $table->id();
            // Minted once at the origin and NEVER rewritten as the packet travels.
            // This immutability is what makes loop suppression work: every node
            // keeps a seen-set keyed on it. See docs/06-ble-protocol.md 6.5.
            $table->uuid('packet_id')->unique();

            // Deliberately NOT a foreign key. Packets can arrive before the
            // emergency row exists (out-of-order multi-hop delivery); the link is
            // reconciled on the business key inside PacketIngestService.
            $table->uuid('emergency_uuid')->index();

            $table->foreignId('origin_device_id')->constrained('devices');
            $table->foreignId('current_device_id')->nullable()->constrained('devices')->nullOnDelete();

            $table->unsignedTinyInteger('hop_count')->default(0);
            $table->unsignedTinyInteger('ttl_remaining')->default(10);
            $table->unsignedTinyInteger('ttl_initial')->default(10);

            $table->char('payload_hash', 64)->nullable();
            $table->string('hmac', 64)->nullable();
            $table->boolean('hmac_valid')->nullable();
            $table->unsignedSmallInteger('payload_bytes')->nullable();

            $table->enum('status', [
                'RECEIVED', 'ACCEPTED', 'DUPLICATE', 'REJECTED',
                'TTL_EXPIRED', 'INVALID_HMAC',
            ])->default('RECEIVED')->index();

            $table->timestamp('created_at_device')->nullable();
            $table->timestamp('received_at_server')->nullable();
            // device clock minus server clock, measured at sync. Without this,
            // transmission-delay figures are unusable. See docs/07-offline-sync.md 7.6.
            $table->integer('clock_offset_ms')->nullable();
            $table->json('route_path')->nullable();

            $table->timestamps();
            $table->index('received_at_server');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('emergency_packets');
    }
};
