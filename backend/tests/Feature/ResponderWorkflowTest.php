<?php

namespace Tests\Feature;

use App\Models\Device;
use App\Models\Emergency;
use App\Models\EmergencyType;
use App\Models\RescueAssignment;
use App\Models\Responder;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Str;
use Laravel\Sanctum\Sanctum;
use Tests\TestCase;

/** Proposal TEST 8: assignment reaches the responder and status advances. */
class ResponderWorkflowTest extends TestCase
{
    use RefreshDatabase;

    private Emergency $incident;
    private Responder $responder;
    private User $operator;

    protected function setUp(): void
    {
        parent::setUp();
        $this->seed(\Database\Seeders\EmergencyTypeSeeder::class);
        $this->seed(\Database\Seeders\SettingSeeder::class);

        $this->operator = User::factory()->create(['role' => 'operator']);
        $this->responder = Responder::factory()->create();

        $this->incident = Emergency::create([
            'emergency_id' => Str::uuid()->toString(),
            'emergency_code' => 'BLG-TEST-0001',
            'emergency_type_id' => EmergencyType::where('code', 'FIRE')->value('id'),
            'description' => 'Structure fire',
            'affected_count' => 3,
            'priority_level' => 'HIGH',
            'priority_score' => 60,
            'status' => 'NEW',
            'origin_device_id' => Device::factory()->create()->id,
            'received_at_server' => now(),
        ]);
    }

    private function assign(): RescueAssignment
    {
        Sanctum::actingAs($this->operator);

        $response = $this->postJson('/api/v1/assignments', [
            'emergency_code' => $this->incident->emergency_code,
            'responder_id' => $this->responder->id,
        ])->assertCreated();

        return RescueAssignment::find($response->json('assignment.id'));
    }

    public function test_assignment_advances_the_incident_and_occupies_the_responder(): void
    {
        $this->assign();

        $this->assertSame('ASSIGNED', $this->incident->fresh()->status);
        $this->assertSame('assigned', $this->responder->fresh()->status);
        $this->assertDatabaseHas('audit_logs', ['action' => 'assignment.created']);
    }

    public function test_responder_sees_the_assignment_in_their_own_list(): void
    {
        $this->assign();

        Sanctum::actingAs($this->responder->user);

        $this->getJson('/api/v1/me/assignments?active=1')
            ->assertOk()
            ->assertJsonPath('assignments.0.emergency.emergency_code', 'BLG-TEST-0001');
    }

    public function test_full_status_progression_keeps_incident_and_assignment_in_step(): void
    {
        $assignment = $this->assign();

        Sanctum::actingAs($this->responder->user);

        $this->patchJson("/api/v1/assignments/{$assignment->id}/accept")->assertOk();

        foreach (['EN_ROUTE', 'ON_SITE', 'RESOLVED'] as $status) {
            $this->patchJson("/api/v1/assignments/{$assignment->id}/status", [
                'status' => $status,
            ])->assertOk()->assertJsonPath('assignment.status', $status);

            $this->assertSame($status, $this->incident->fresh()->status);
        }

        $incident = $this->incident->fresh();
        $this->assertNotNull($incident->resolved_at);
        $this->assertSame('available', $this->responder->fresh()->status);

        // Every transition leaves a trace: NEW, ASSIGNED, EN_ROUTE, ON_SITE, RESOLVED.
        $this->assertSame(4, $incident->statusHistory()->count());
    }

    public function test_declining_returns_the_incident_to_the_queue(): void
    {
        $assignment = $this->assign();

        Sanctum::actingAs($this->responder->user);

        $this->patchJson("/api/v1/assignments/{$assignment->id}/decline", [
            'reason' => 'Already committed to another incident.',
        ])->assertOk();

        $this->assertSame('TRIAGED', $this->incident->fresh()->status);
        $this->assertSame('available', $this->responder->fresh()->status);
    }

    public function test_another_responder_cannot_act_on_the_assignment(): void
    {
        $assignment = $this->assign();
        $stranger = Responder::factory()->create();

        Sanctum::actingAs($stranger->user);

        $this->patchJson("/api/v1/assignments/{$assignment->id}/accept")->assertForbidden();
    }

    public function test_invalid_status_transitions_are_refused(): void
    {
        $assignment = $this->assign();

        Sanctum::actingAs($this->responder->user);

        // A responder cannot jump straight from ASSIGNED to RESOLVED: an
        // incident is closed from the scene, or cancelled by an operator.
        $this->patchJson("/api/v1/assignments/{$assignment->id}/status", ['status' => 'RESOLVED'])
            ->assertStatus(422)
            ->assertJsonPath('code', 'INVALID_TRANSITION');

        $this->patchJson("/api/v1/assignments/{$assignment->id}/status", ['status' => 'ON_SITE'])
            ->assertOk();
        $this->patchJson("/api/v1/assignments/{$assignment->id}/status", ['status' => 'RESOLVED'])
            ->assertOk();

        // Once closed, the incident must not walk backwards.
        $this->patchJson("/api/v1/assignments/{$assignment->id}/status", ['status' => 'EN_ROUTE'])
            ->assertStatus(422)
            ->assertJsonPath('code', 'INVALID_TRANSITION');
    }

    public function test_a_closed_incident_cannot_be_assigned(): void
    {
        $this->incident->update(['status' => 'RESOLVED']);

        Sanctum::actingAs($this->operator);

        $this->postJson('/api/v1/assignments', [
            'emergency_code' => $this->incident->emergency_code,
            'responder_id' => $this->responder->id,
        ])->assertStatus(422)->assertJsonPath('code', 'INCIDENT_CLOSED');
    }

    public function test_assignment_requires_an_assignee(): void
    {
        Sanctum::actingAs($this->operator);

        $this->postJson('/api/v1/assignments', [
            'emergency_code' => $this->incident->emergency_code,
        ])->assertStatus(422)->assertJsonPath('code', 'ASSIGNEE_REQUIRED');
    }
}
