<?php

namespace Tests\Feature;

use App\Models\Device;
use App\Models\Emergency;
use App\Models\EmergencyPacket;
use App\Models\PacketLog;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Str;
use Laravel\Sanctum\Sanctum;
use Tests\Support\PacketBuilder;
use Tests\TestCase;

/**
 * Covers the idempotency contract: a packet may arrive any number of times, by
 * any number of routes, in any order, and the database must end up the same.
 *
 * @see docs/07-offline-sync.md
 */
class PacketIngestionTest extends TestCase
{
    use RefreshDatabase;

    protected Device $device;

    protected function setUp(): void
    {
        parent::setUp();
        $this->seed(\Database\Seeders\EmergencyTypeSeeder::class);
        $this->seed(\Database\Seeders\SettingSeeder::class);

        $this->device = Device::factory()->create();
        Sanctum::actingAs($this->device, ['sync:push', 'sync:pull']);
    }

    private function push(array $batch)
    {
        return $this->postJson('/api/v1/sync/packets', $batch);
    }

    public function test_a_new_packet_creates_one_incident(): void
    {
        $batch = PacketBuilder::for($this->device)->signed()->batch();

        $this->push($batch)
            ->assertOk()
            ->assertJsonPath('results.0.status', 'ACCEPTED')
            ->assertJsonPath('summary.accepted', 1);

        $this->assertSame(1, Emergency::count());
        $this->assertNotNull(Emergency::first()->emergency_code);
    }

    /** TEST 4: the same packet arriving twice must not become two incidents. */
    public function test_replaying_the_same_packet_is_a_duplicate(): void
    {
        $batch = PacketBuilder::for($this->device)->signed()->batch();

        $this->push($batch)->assertJsonPath('results.0.status', 'ACCEPTED');
        $this->push($batch)->assertJsonPath('results.0.status', 'DUPLICATE');

        $this->assertSame(1, Emergency::count());
        $this->assertSame(1, EmergencyPacket::count());
        $this->assertSame(1, PacketLog::where('event', 'DUPLICATE_SUPPRESSED')->count());
    }

    /**
     * The same emergency reaching the server by two different routes. Both
     * packets are kept as routing evidence, but only one incident exists.
     */
    public function test_two_packets_for_one_emergency_create_one_incident(): void
    {
        $emergencyId = Str::uuid()->toString();

        $first = PacketBuilder::for($this->device)
            ->with(['emergency_id' => $emergencyId])->hops(1)->signed()->batch();
        $second = PacketBuilder::for($this->device)
            ->with(['emergency_id' => $emergencyId])->hops(3)->signed()->batch();

        $this->push($first)->assertJsonPath('results.0.status', 'ACCEPTED');
        $this->push($second)->assertJsonPath('results.0.status', 'ACCEPTED');

        $this->assertSame(1, Emergency::count());
        $this->assertSame(2, EmergencyPacket::count());
    }

    /** The earliest known route is what the incident reports, whatever order they arrive in. */
    public function test_first_hop_count_keeps_the_shortest_route(): void
    {
        $emergencyId = Str::uuid()->toString();

        $this->push(PacketBuilder::for($this->device)
            ->with(['emergency_id' => $emergencyId])->hops(4)->signed()->batch());
        $this->push(PacketBuilder::for($this->device)
            ->with(['emergency_id' => $emergencyId])->hops(2)->signed()->batch());

        $this->assertSame(2, Emergency::first()->first_hop_count);
    }

    /** TEST 5: a packet at TTL 0 stops being forwarded, but must still be delivered. */
    public function test_ttl_expired_packet_is_still_accepted(): void
    {
        $batch = PacketBuilder::for($this->device)->hops(10, 0)->signed()->batch();

        $this->push($batch)->assertJsonPath('results.0.status', 'TTL_EXPIRED_ACCEPTED');

        $this->assertSame(1, Emergency::count());
        $this->assertSame('TTL_EXPIRED', EmergencyPacket::first()->status);
    }

    public function test_tampered_payload_is_rejected_and_creates_no_incident(): void
    {
        $batch = PacketBuilder::for($this->device)->tampered()->batch();

        $this->push($batch)->assertJsonPath('results.0.status', 'INVALID_HMAC');

        $this->assertSame(0, Emergency::count());
        $this->assertFalse(EmergencyPacket::first()->hmac_valid);
    }

    /**
     * A device that has never reached the server can still report: requiring
     * registration first would reintroduce the internet dependency this whole
     * architecture exists to remove.
     */
    public function test_packet_from_unregistered_device_is_accepted_unverified(): void
    {
        $origin = Device::factory()->unregisteredKey()->create();
        $batch = PacketBuilder::for($origin)->batch();

        $this->push($batch)->assertJsonPath('results.0.status', 'ACCEPTED');

        $this->assertSame(1, Emergency::count());
        $this->assertNull(EmergencyPacket::first()->hmac_valid);
    }

    public function test_unknown_emergency_type_is_rejected(): void
    {
        $batch = PacketBuilder::for($this->device)
            ->payload(['type_code' => 'NOT_A_TYPE'])->signed()->batch();

        $this->push($batch)->assertJsonPath('results.0.status', 'REJECTED');
        $this->assertSame(0, Emergency::count());
    }

    /** A late packet must not resurrect an incident an operator already closed. */
    public function test_late_packet_does_not_overwrite_operator_state(): void
    {
        $emergencyId = Str::uuid()->toString();

        $this->push(PacketBuilder::for($this->device)
            ->with(['emergency_id' => $emergencyId])->hops(1)->signed()->batch());

        $emergency = Emergency::first();
        $emergency->update(['status' => 'RESOLVED', 'priority_level' => 'LOW']);

        $this->push(PacketBuilder::for($this->device)
            ->with(['emergency_id' => $emergencyId])->hops(5)->signed()->batch());

        $emergency->refresh();
        $this->assertSame('RESOLVED', $emergency->status);
        $this->assertSame('LOW', $emergency->priority_level);
        $this->assertSame(2, EmergencyPacket::count());
    }

    public function test_replaying_an_entire_batch_leaves_state_unchanged(): void
    {
        $batch = [
            'client_clock' => now()->toIso8601String(),
            'packets' => [
                PacketBuilder::for($this->device)->signed()->toArray(),
                PacketBuilder::for($this->device)->hops(2)->signed()->toArray(),
                PacketBuilder::for($this->device)->hops(4)->signed()->toArray(),
            ],
        ];

        $this->push($batch)->assertJsonPath('summary.accepted', 3);

        $snapshot = [Emergency::count(), EmergencyPacket::count()];

        $this->push($batch)->assertJsonPath('summary.duplicate', 3);

        $this->assertSame($snapshot, [Emergency::count(), EmergencyPacket::count()]);
    }

    public function test_a_revoked_device_cannot_sync(): void
    {
        $revoked = Device::factory()->revoked()->create();
        Sanctum::actingAs($revoked, ['sync:push']);

        $this->push(PacketBuilder::for($revoked)->batch())
            ->assertForbidden()
            ->assertJsonPath('code', 'DEVICE_REVOKED');
    }

    public function test_device_token_cannot_read_incidents(): void
    {
        $this->getJson('/api/v1/incidents')
            ->assertForbidden()
            ->assertJsonPath('code', 'USER_TOKEN_REQUIRED');
    }
}
