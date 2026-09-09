<?php

namespace Database\Seeders;

use App\Models\Device;
use App\Models\Emergency;
use App\Models\EmergencyType;
use App\Models\PacketLog;
use App\Models\RescueTeam;
use App\Models\Responder;
use App\Models\StatusHistory;
use App\Models\SyncLog;
use App\Models\User;
use App\Services\Priority\PriorityEngine;
use Illuminate\Database\Seeder;
use Illuminate\Support\Str;

/**
 * Synthetic demonstration data.
 *
 * Every record here is invented. No real resident's emergency, name, contact or
 * location appears in this repository (section 22, data minimisation). The
 * coordinates are plausible points within Tacloban City and are placeholders
 * until the pilot barangay is confirmed — marked TO BE VALIDATED in the docs.
 */
class DemoDataSeeder extends Seeder
{
    public function run(): void
    {
        $engine = app(PriorityEngine::class);

        $staff = [
            ['Maria Operator', 'operator@bulig.test', 'operator'],
            ['Jose Official', 'official@bulig.test', 'official'],
            ['Ana Sysadmin', 'sysadmin@bulig.test', 'sysadmin'],
        ];
        foreach ($staff as [$name, $email, $role]) {
            User::updateOrCreate(['email' => $email], [
                'name' => $name, 'password' => 'password', 'role' => $role, 'is_active' => true,
            ]);
        }

        $resident = User::updateOrCreate(['email' => 'resident@bulig.test'], [
            'name' => 'Pedro Resident', 'password' => 'password', 'role' => 'resident', 'is_active' => true,
        ]);

        $teams = collect([
            ['Team Alpha', 'ALPHA', 'Barangay Hall'],
            ['Team Bravo', 'BRAVO', 'Purok 3 Outpost'],
        ])->map(fn ($t) => RescueTeam::updateOrCreate(['code' => $t[1]], [
            'name' => $t[0], 'base_location' => $t[2],
            'base_latitude' => 11.2444, 'base_longitude' => 125.0048, 'is_active' => true,
        ]));

        $responders = collect([
            ['Rico Santos', 'responder1@bulig.test', $teams[0]],
            ['Lita Cruz', 'responder2@bulig.test', $teams[0]],
            ['Ben Reyes', 'responder3@bulig.test', $teams[1]],
        ])->map(function ($r) {
            $user = User::updateOrCreate(['email' => $r[1]], [
                'name' => $r[0], 'password' => 'password', 'role' => 'responder', 'is_active' => true,
            ]);

            return Responder::updateOrCreate(['user_id' => $user->id], [
                'rescue_team_id' => $r[2]->id,
                'badge_no' => 'BR-'.Str::upper(Str::random(4)),
                'status' => 'available',
            ]);
        });

        $devices = collect(range(1, 5))->map(fn ($i) => Device::updateOrCreate(
            ['device_id' => Str::uuid()->toString()],
            [
                'user_id' => $i === 1 ? $resident->id : null,
                'label' => "Demo relay {$i}",
                'model' => ['Redmi 9A', 'Galaxy A12', 'Vivo Y15', 'Realme C21', 'Galaxy A03'][$i - 1],
                'android_version' => ['11', '12', '11', '13', '12'][$i - 1],
                'hmac_key' => random_bytes(32),
                'supports_advertising' => $i !== 5,
                'last_seen_at' => now()->subMinutes($i * 3),
            ]
        ));

        // Each entry: type, description, affected, children, elderly, mobility,
        // life-threatening, hops taken to escape, minutes ago, status.
        $scenarios = [
            ['MEDICAL', 'Elderly man collapsed and is not responding.', 4, 0, 2, 1, true, 3, 6, 'NEW'],
            ['FIRE', 'House fire spreading to the next roof.', 12, 3, 2, 0, true, 1, 22, 'ASSIGNED'],
            ['FLOOD', 'Water rising past knee level along the creek.', 8, 2, 1, 0, false, 2, 48, 'EN_ROUTE'],
            ['TRAPPED', 'Two people trapped under a collapsed wall.', 2, 0, 0, 1, true, 0, 15, 'ON_SITE'],
            ['RESCUE', 'Family stranded on a rooftop.', 5, 1, 1, 0, false, 2, 90, 'ASSIGNED'],
            ['INFRA', 'Footbridge handrail washed away.', 1, 0, 0, 0, false, 0, 200, 'NEW'],
            ['MISSING', 'Child last seen near the shoreline.', 1, 1, 0, 0, false, 1, 70, 'NEW'],
            ['MEDICAL', 'Pregnant woman needs transport to the health center.', 1, 0, 0, 1, false, 0, 12, 'RESOLVED'],
        ];

        foreach ($scenarios as $i => $s) {
            [$code, $desc, $affected, $children, $elderly, $mobility, $lifeThreat, $hops, $minsAgo, $status] = $s;

            $type = EmergencyType::where('code', $code)->first();
            $device = $devices[$i % $devices->count()];
            $deviceTime = now()->subMinutes($minsAgo);

            $emergency = new Emergency([
                'emergency_id' => Str::uuid()->toString(),
                'emergency_code' => sprintf('BLG-%d-%04d', now()->year, $i + 1),
                'emergency_type_id' => $type->id,
                'description' => $desc,
                'affected_count' => $affected,
                'children_count' => $children,
                'elderly_count' => $elderly,
                'mobility_limited_count' => $mobility,
                'is_life_threatening' => $lifeThreat,
                'status' => $status,
                'origin_device_id' => $device->id,
                'reported_by_user_id' => $i === 0 ? $resident->id : null,
                'created_at_device' => $deviceTime,
                'received_at_server' => $deviceTime->copy()->addSeconds(20 + $hops * 45),
                'first_hop_count' => $hops,
                'resolved_at' => $status === 'RESOLVED' ? now()->subMinutes(2) : null,
            ]);
            $emergency->setRelation('type', $type);

            $result = $engine->scoreEmergency($emergency);
            $emergency->priority_score = $result->score;
            $emergency->priority_level = $result->level;
            $emergency->priority_breakdown = $result->toBreakdown();
            $emergency->save();

            $emergency->location()->create([
                'latitude' => 11.2444 + (mt_rand(-160, 160) / 10000),
                'longitude' => 125.0048 + (mt_rand(-160, 160) / 10000),
                'accuracy_m' => mt_rand(5, 45),
                'provider' => $hops > 1 ? 'network' : 'gps',
                'is_approximate' => false,
                'captured_at' => $deviceTime,
            ]);

            StatusHistory::create([
                'emergency_id' => $emergency->id,
                'to_status' => 'NEW',
                'source' => 'sync',
                'note' => "Received via {$hops} hop(s).",
                'occurred_at' => $emergency->received_at_server,
            ]);

            $this->seedPackets($emergency, $device, $devices, $hops);

            if (in_array($status, ['ASSIGNED', 'EN_ROUTE', 'ON_SITE', 'RESOLVED'], true)) {
                $this->seedAssignment($emergency, $responders->random(), $status);
            }
        }
    }

    /**
     * Recreates the routing evidence a real multi-hop delivery would leave behind,
     * including a suppressed duplicate — the command center's network monitoring
     * page and the evaluation metrics both read from these rows.
     */
    private function seedPackets(Emergency $emergency, Device $origin, $devices, int $hops): void
    {
        $packetId = Str::uuid()->toString();
        $carrier = $devices->random();
        $ttlInitial = 10;

        $syncLog = SyncLog::create([
            'device_id' => $carrier->id,
            'direction' => 'push',
            'packets_sent' => 1,
            'packets_accepted' => 1,
            'bytes' => mt_rand(220, 400),
            'started_at' => $emergency->received_at_server,
            'completed_at' => $emergency->received_at_server->copy()->addMilliseconds(340),
            'duration_ms' => 340,
            'outcome' => 'success',
            'client_clock_at_start' => $emergency->received_at_server,
        ]);

        $emergency->packets()->create([
            'packet_id' => $packetId,
            'origin_device_id' => $origin->id,
            'current_device_id' => $carrier->id,
            'hop_count' => $hops,
            'ttl_remaining' => $ttlInitial - $hops,
            'ttl_initial' => $ttlInitial,
            'payload_hash' => hash('sha256', $emergency->emergency_id),
            'hmac_valid' => true,
            'payload_bytes' => mt_rand(220, 400),
            'status' => 'ACCEPTED',
            'created_at_device' => $emergency->created_at_device,
            'received_at_server' => $emergency->received_at_server,
            'clock_offset_ms' => mt_rand(-4000, 4000),
        ]);

        PacketLog::create([
            'packet_id' => $packetId, 'device_id' => $origin->id, 'event' => 'CREATED',
            'hop_count' => 0, 'ttl_remaining' => $ttlInitial,
            'occurred_at' => $emergency->created_at_device,
        ]);

        for ($h = 1; $h <= $hops; $h++) {
            PacketLog::create([
                'packet_id' => $packetId, 'device_id' => $devices->random()->id,
                'event' => 'RELAY_RECEIVED', 'hop_count' => $h, 'ttl_remaining' => $ttlInitial - $h,
                'occurred_at' => $emergency->created_at_device->copy()->addSeconds($h * 40),
            ]);
        }

        PacketLog::create([
            'packet_id' => $packetId, 'sync_log_id' => $syncLog->id, 'device_id' => $carrier->id,
            'event' => 'SYNC_ACCEPTED', 'hop_count' => $hops, 'ttl_remaining' => $ttlInitial - $hops,
            'occurred_at' => $emergency->received_at_server,
        ]);

        // Multi-hop delivery reaches some nodes twice; the seen-set stops it there.
        if ($hops >= 2) {
            PacketLog::create([
                'packet_id' => $packetId, 'device_id' => $devices->random()->id,
                'event' => 'DUPLICATE_SUPPRESSED', 'hop_count' => $hops,
                'ttl_remaining' => $ttlInitial - $hops,
                'detail' => ['reason' => 'packet_id already in seen-set'],
                'occurred_at' => $emergency->created_at_device->copy()->addSeconds($hops * 50),
            ]);
        }
    }

    private function seedAssignment(Emergency $emergency, Responder $responder, string $status): void
    {
        $map = [
            'ASSIGNED' => 'ASSIGNED',
            'EN_ROUTE' => 'EN_ROUTE',
            'ON_SITE' => 'ON_SITE',
            'RESOLVED' => 'RESOLVED',
        ];

        $assignedAt = $emergency->received_at_server->copy()->addMinutes(2);
        $official = User::where('role', 'official')->first();

        $emergency->assignments()->create([
            'rescue_team_id' => $responder->rescue_team_id,
            'responder_id' => $responder->id,
            'assigned_by_user_id' => $official->id,
            'status' => $map[$status],
            'assigned_at' => $assignedAt,
            'accepted_at' => $assignedAt->copy()->addMinutes(1),
            'en_route_at' => in_array($status, ['EN_ROUTE', 'ON_SITE', 'RESOLVED'], true)
                ? $assignedAt->copy()->addMinutes(3) : null,
            'on_site_at' => in_array($status, ['ON_SITE', 'RESOLVED'], true)
                ? $assignedAt->copy()->addMinutes(9) : null,
            'resolved_at' => $status === 'RESOLVED' ? $assignedAt->copy()->addMinutes(20) : null,
            'persons_assisted' => $status === 'RESOLVED' ? $emergency->affected_count : null,
        ]);

        StatusHistory::create([
            'emergency_id' => $emergency->id,
            'from_status' => 'NEW', 'to_status' => $status,
            'changed_by_user_id' => $official->id, 'source' => 'operator',
            'occurred_at' => $assignedAt,
        ]);
    }
}
