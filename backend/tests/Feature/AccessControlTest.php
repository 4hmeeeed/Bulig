<?php

namespace Tests\Feature;

use App\Models\Emergency;
use App\Models\EmergencyType;
use App\Models\Device;
use App\Models\RescueTeam;
use App\Models\Responder;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Str;
use Laravel\Sanctum\Sanctum;
use Tests\TestCase;

/**
 * Emergency reports name vulnerable people. These tests guard the boundaries
 * that keep that information away from anyone without a reason to see it.
 *
 * @see docs/02-roles-permissions.md
 */
class AccessControlTest extends TestCase
{
    use RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        $this->seed(\Database\Seeders\EmergencyTypeSeeder::class);
        $this->seed(\Database\Seeders\SettingSeeder::class);
    }

    private function makeIncident(string $priority = 'MODERATE'): Emergency
    {
        return Emergency::create([
            'emergency_id' => Str::uuid()->toString(),
            'emergency_code' => 'BLG-TEST-'.Str::upper(Str::random(4)),
            'emergency_type_id' => EmergencyType::where('code', 'MEDICAL')->value('id'),
            'description' => 'Test incident',
            'affected_count' => 2,
            'priority_level' => $priority,
            'priority_score' => 40,
            'status' => 'NEW',
            'origin_device_id' => Device::factory()->create()->id,
            'received_at_server' => now(),
        ]);
    }

    private function makeResponder(?RescueTeam $team = null): Responder
    {
        return Responder::factory()->create(['rescue_team_id' => $team?->id]);
    }

    public function test_responder_cannot_read_an_incident_they_are_not_assigned_to(): void
    {
        $incident = $this->makeIncident();
        $responder = $this->makeResponder();

        Sanctum::actingAs($responder->user);

        $this->getJson("/api/v1/incidents/{$incident->emergency_code}")->assertForbidden();
    }

    public function test_responder_can_read_an_incident_assigned_to_them(): void
    {
        $incident = $this->makeIncident();
        $responder = $this->makeResponder();
        $operator = User::factory()->create(['role' => 'operator']);

        $incident->assignments()->create([
            'responder_id' => $responder->id,
            'assigned_by_user_id' => $operator->id,
            'status' => 'ASSIGNED',
            'assigned_at' => now(),
        ]);

        Sanctum::actingAs($responder->user);

        $this->getJson("/api/v1/incidents/{$incident->emergency_code}")->assertOk();
    }

    /** Team assignment is enough: a colleague may be the one who responds. */
    public function test_responder_can_read_an_incident_assigned_to_their_team(): void
    {
        $team = RescueTeam::factory()->create();
        $incident = $this->makeIncident();
        $mine = $this->makeResponder($team);
        $colleague = $this->makeResponder($team);
        $operator = User::factory()->create(['role' => 'operator']);

        $incident->assignments()->create([
            'responder_id' => $colleague->id,
            'rescue_team_id' => $team->id,
            'assigned_by_user_id' => $operator->id,
            'status' => 'ASSIGNED',
            'assigned_at' => now(),
        ]);

        Sanctum::actingAs($mine->user);

        $this->getJson("/api/v1/incidents/{$incident->emergency_code}")->assertOk();
    }

    /** The list endpoint must scope in the query, not merely in the policy. */
    public function test_responder_incident_list_is_scoped_to_their_assignments(): void
    {
        $this->makeIncident();
        $this->makeIncident();
        $assigned = $this->makeIncident();

        $responder = $this->makeResponder();
        $operator = User::factory()->create(['role' => 'operator']);

        $assigned->assignments()->create([
            'responder_id' => $responder->id,
            'assigned_by_user_id' => $operator->id,
            'status' => 'ASSIGNED',
            'assigned_at' => now(),
        ]);

        Sanctum::actingAs($responder->user);

        $this->getJson('/api/v1/incidents')
            ->assertOk()
            ->assertJsonPath('total', 1)
            ->assertJsonPath('data.0.emergency_code', $assigned->emergency_code);
    }

    public function test_operator_may_raise_priority_but_not_lower_it(): void
    {
        $incident = $this->makeIncident('MODERATE');
        Sanctum::actingAs(User::factory()->create(['role' => 'operator']));

        $this->patchJson("/api/v1/incidents/{$incident->emergency_code}/priority", [
            'level' => 'CRITICAL', 'reason' => 'Situation worsened on scene.',
        ])->assertOk();

        $this->patchJson("/api/v1/incidents/{$incident->emergency_code}/priority", [
            'level' => 'LOW', 'reason' => 'Attempting to downgrade.',
        ])->assertForbidden()->assertJsonPath('code', 'PRIORITY_LOWER_FORBIDDEN');
    }

    public function test_official_may_lower_priority(): void
    {
        $incident = $this->makeIncident('CRITICAL');
        Sanctum::actingAs(User::factory()->create(['role' => 'official']));

        $this->patchJson("/api/v1/incidents/{$incident->emergency_code}/priority", [
            'level' => 'LOW', 'reason' => 'Confirmed false alarm by barangay official.',
        ])->assertOk();

        $this->assertSame('LOW', $incident->fresh()->priority_level);
    }

    public function test_priority_override_requires_a_reason(): void
    {
        $incident = $this->makeIncident();
        Sanctum::actingAs(User::factory()->create(['role' => 'official']));

        $this->patchJson("/api/v1/incidents/{$incident->emergency_code}/priority", [
            'level' => 'HIGH',
        ])->assertStatus(422);
    }

    public function test_priority_override_is_audited(): void
    {
        $incident = $this->makeIncident('MODERATE');
        $official = User::factory()->create(['role' => 'official']);
        Sanctum::actingAs($official);

        $this->patchJson("/api/v1/incidents/{$incident->emergency_code}/priority", [
            'level' => 'CRITICAL', 'reason' => 'Escalated after radio report.',
        ])->assertOk();

        $this->assertDatabaseHas('audit_logs', [
            'user_id' => $official->id,
            'action' => 'incident.priority_overridden',
            'subject_id' => $incident->id,
        ]);
    }

    public function test_responder_cannot_read_evaluation_metrics(): void
    {
        Sanctum::actingAs($this->makeResponder()->user);

        $this->getJson('/api/v1/metrics/evaluation')
            ->assertForbidden()
            ->assertJsonPath('code', 'FORBIDDEN_ROLE');
    }

    public function test_disabled_account_cannot_use_the_api(): void
    {
        Sanctum::actingAs(User::factory()->create(['role' => 'operator', 'is_active' => false]));

        $this->getJson('/api/v1/incidents')
            ->assertForbidden()
            ->assertJsonPath('code', 'ACCOUNT_DISABLED');
    }

    public function test_residents_and_responders_cannot_reach_the_command_center(): void
    {
        $responder = $this->makeResponder();

        $this->post('/login', [
            'email' => $responder->user->email,
            'password' => 'password',
        ])->assertSessionHasErrors('email');

        $this->assertGuest();
    }
}
