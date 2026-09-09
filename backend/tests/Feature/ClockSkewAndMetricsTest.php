<?php

namespace Tests\Feature;

use App\Models\Device;
use App\Models\Emergency;
use App\Models\EmergencyPacket;
use App\Services\EvaluationMetricsService;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Laravel\Sanctum\Sanctum;
use Tests\Support\PacketBuilder;
use Tests\TestCase;

/**
 * Offline phones drift. Without correcting for the offset measured at sync, a
 * fast clock yields a negative transmission delay and the evaluation chapter
 * reports nonsense.
 *
 * @see docs/07-offline-sync.md 7.6
 */
class ClockSkewAndMetricsTest extends TestCase
{
    use RefreshDatabase;

    private Device $device;

    protected function setUp(): void
    {
        parent::setUp();
        $this->seed(\Database\Seeders\EmergencyTypeSeeder::class);
        $this->seed(\Database\Seeders\SettingSeeder::class);

        $this->device = Device::factory()->create();
        Sanctum::actingAs($this->device, ['sync:push']);
    }

    public function test_clock_offset_is_recorded_from_the_sync_envelope(): void
    {
        $packet = PacketBuilder::for($this->device)->signed()->toArray();

        $this->postJson('/api/v1/sync/packets', [
            // The device believes it is 40 minutes later than it really is.
            'client_clock' => now()->addMinutes(40)->toIso8601String(),
            'packets' => [$packet],
        ])->assertOk();

        $stored = EmergencyPacket::first();

        $this->assertGreaterThan(0, $stored->clock_offset_ms);
        $this->assertEqualsWithDelta(40 * 60 * 1000, $stored->clock_offset_ms, 5000);
    }

    /** The correction is what turns an impossible delay into a usable one. */
    public function test_corrected_delay_is_positive_despite_a_fast_device_clock(): void
    {
        $packet = PacketBuilder::for($this->device)
            // The device stamps creation 39 minutes in the future by server time.
            ->with(['created_at_device' => now()->addMinutes(39)->toIso8601String()])
            ->signed()->toArray();

        $this->postJson('/api/v1/sync/packets', [
            'client_clock' => now()->addMinutes(40)->toIso8601String(),
            'packets' => [$packet],
        ])->assertOk();

        $stored = EmergencyPacket::first();

        // Raw arithmetic would give a negative delay; the correction rescues it.
        $raw = $stored->received_at_server->getTimestampMs() - $stored->created_at_device->getTimestampMs();
        $this->assertLessThan(0, $raw);
        $this->assertGreaterThan(0, $stored->correctedDelayMs());
    }

    public function test_unreconcilable_clock_is_flagged_on_the_incident(): void
    {
        $packet = PacketBuilder::for($this->device)
            ->with(['created_at_device' => now()->addHours(6)->toIso8601String()])
            ->signed()->toArray();

        $this->postJson('/api/v1/sync/packets', [
            'client_clock' => now()->toIso8601String(),
            'packets' => [$packet],
        ])->assertOk();

        $this->assertTrue(Emergency::first()->clock_anomaly);
    }

    public function test_anomalous_delays_are_excluded_from_metrics_not_averaged_in(): void
    {
        // One sane packet, one with an unusable clock.
        $this->postJson('/api/v1/sync/packets', [
            'client_clock' => now()->toIso8601String(),
            'packets' => [PacketBuilder::for($this->device)->signed()->toArray()],
        ])->assertOk();

        $this->postJson('/api/v1/sync/packets', [
            'client_clock' => now()->toIso8601String(),
            'packets' => [
                PacketBuilder::for($this->device)
                    ->with(['created_at_device' => now()->addHours(6)->toIso8601String()])
                    ->signed()->toArray(),
            ],
        ])->assertOk();

        $delay = app(EvaluationMetricsService::class)->transmissionDelay();

        $this->assertSame(1, $delay['n']);
        $this->assertSame(1, $delay['excluded_clock_anomaly']);
        $this->assertGreaterThanOrEqual(0, $delay['median']);
    }

    /** Metric 7 must always read zero: a packet cannot outrun its own TTL. */
    public function test_no_packet_ever_exceeds_its_initial_ttl(): void
    {
        foreach ([0, 1, 3, 7, 10] as $hops) {
            $this->postJson('/api/v1/sync/packets', [
                'client_clock' => now()->toIso8601String(),
                'packets' => [PacketBuilder::for($this->device)->hops($hops)->signed()->toArray()],
            ])->assertOk();
        }

        $metrics = app(EvaluationMetricsService::class)->all();

        $this->assertSame(0, $metrics['ttl_enforcement']['ttl_violations']);
        $this->assertSame(1, $metrics['ttl_enforcement']['ttl_expired_packets']);
        $this->assertSame(3, $metrics['delivery']['multi_hop']);
    }

    public function test_duplicate_metrics_show_suppression_without_extra_incidents(): void
    {
        $batch = PacketBuilder::for($this->device)->signed()->batch();

        $this->postJson('/api/v1/sync/packets', $batch)->assertOk();
        $this->postJson('/api/v1/sync/packets', $batch)->assertOk();
        $this->postJson('/api/v1/sync/packets', $batch)->assertOk();

        $metrics = app(EvaluationMetricsService::class)->duplicateSuppression();

        $this->assertSame(2, $metrics['suppressed']);
        $this->assertSame(1, $metrics['distinct_emergencies']);
        $this->assertSame(1, $metrics['distinct_packets']);
    }

    public function test_sync_log_records_the_batch_outcome(): void
    {
        $this->postJson('/api/v1/sync/packets', [
            'client_clock' => now()->toIso8601String(),
            'packets' => [
                PacketBuilder::for($this->device)->signed()->toArray(),
                PacketBuilder::for($this->device)->payload(['type_code' => 'BOGUS'])->signed()->toArray(),
            ],
        ])->assertOk()
            ->assertJsonPath('summary.accepted', 1)
            ->assertJsonPath('summary.rejected', 1);

        $this->assertDatabaseHas('sync_logs', [
            'packets_sent' => 2,
            'packets_accepted' => 1,
            'packets_rejected' => 1,
            'outcome' => 'partial',
        ]);
    }
}
