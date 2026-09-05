<?php

namespace Tests\Feature;

use App\Livewire\Dashboard;
use App\Livewire\IncidentDetail;
use App\Livewire\IncidentQueue;
use App\Livewire\PacketMonitor;
use App\Models\Emergency;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Livewire\Livewire;
use Tests\TestCase;

class CommandCenterTest extends TestCase
{
    use RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        $this->seed();
    }

    private function asOfficial(): User
    {
        $user = User::where('role', 'official')->firstOrFail();
        $this->actingAs($user);

        return $user;
    }

    public function test_dashboard_renders_with_stats_and_markers(): void
    {
        $this->asOfficial();

        Livewire::test(Dashboard::class)
            ->assertOk()
            ->assertSee('Active incidents')
            ->assertSee('BLG-2026-0001');
    }

    public function test_incident_queue_filters_by_priority(): void
    {
        $this->asOfficial();

        Livewire::test(IncidentQueue::class)
            ->set('priority', 'CRITICAL')
            ->assertSee('CRITICAL')
            ->assertDontSee('BLG-2026-0006'); // a LOW infrastructure report
    }

    public function test_incident_detail_shows_the_priority_rule_trace(): void
    {
        $this->asOfficial();

        Livewire::test(IncidentDetail::class, ['code' => 'BLG-2026-0001'])
            ->assertOk()
            ->assertSee('Why this priority')
            ->assertSee('Base severity')
            ->assertSee('Delivery route');
    }

    public function test_operator_can_assign_a_responder_from_the_incident_page(): void
    {
        $this->actingAs(User::where('role', 'operator')->firstOrFail());

        $incident = Emergency::where('emergency_code', 'BLG-2026-0001')->firstOrFail();
        $responder = \App\Models\Responder::first();

        Livewire::test(IncidentDetail::class, ['code' => $incident->emergency_code])
            ->set('assignResponderId', $responder->id)
            ->call('assign')
            ->assertHasNoErrors();

        $this->assertSame('ASSIGNED', $incident->fresh()->status);
    }

    public function test_operator_cannot_lower_priority_from_the_incident_page(): void
    {
        $this->actingAs(User::where('role', 'operator')->firstOrFail());

        Livewire::test(IncidentDetail::class, ['code' => 'BLG-2026-0002'])
            ->set('overrideLevel', 'LOW')
            ->set('overrideReason', 'Attempting to downgrade a critical incident.')
            ->call('overridePriority')
            ->assertHasErrors('overrideLevel');

        $this->assertSame('CRITICAL',
            Emergency::where('emergency_code', 'BLG-2026-0002')->value('priority_level'));
    }

    public function test_packet_monitor_reports_mesh_metrics(): void
    {
        $this->asOfficial();

        Livewire::test(PacketMonitor::class)
            ->assertOk()
            ->assertSee('Duplicates suppressed')
            ->assertSee('TTL violations')
            ->assertSee('Delivery by hop count');
    }

    public function test_operator_cannot_reach_administration_pages(): void
    {
        $this->actingAs(User::where('role', 'operator')->firstOrFail());

        $this->get('/users')->assertForbidden();
        $this->get('/settings')->assertForbidden();
        $this->get('/audit-logs')->assertForbidden();
    }

    public function test_guests_are_redirected_to_login(): void
    {
        $this->get('/dashboard')->assertRedirect('/login');
    }
}
