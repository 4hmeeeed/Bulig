<?php

namespace App\Livewire;

use App\Models\Emergency;
use App\Models\PacketLog;
use App\Models\Responder;
use App\Services\AuditLogger;
use App\Services\IncidentStatusService;
use Illuminate\Support\Facades\Gate;
use Livewire\Attributes\Layout;
use Livewire\Component;
use RuntimeException;

#[Layout('layouts.app')]
class IncidentDetail extends Component
{
    public string $code;
    public string $pageTitle = 'Incident';

    public ?int $assignResponderId = null;
    public string $assignNotes = '';
    public string $overrideLevel = '';
    public string $overrideReason = '';

    public function mount(string $code): void
    {
        $this->code = $code;
        Gate::authorize('view', $this->incident());
    }

    private function incident(): Emergency
    {
        return Emergency::where('emergency_code', $this->code)
            ->with([
                'type', 'location', 'originDevice',
                'assignments.responder.user', 'assignments.team',
                'statusHistory.changedBy:id,name',
                'priorityOverriddenBy:id,name',
            ])
            ->firstOrFail();
    }

    public function assign(): void
    {
        $incident = $this->incident();
        Gate::authorize('assign', $incident);

        $this->validate([
            'assignResponderId' => ['required', 'integer', 'exists:responders,id'],
            'assignNotes' => ['nullable', 'string', 'max:1000'],
        ]);

        $responder = Responder::findOrFail($this->assignResponderId);

        $assignment = $incident->assignments()->create([
            'responder_id' => $responder->id,
            'rescue_team_id' => $responder->rescue_team_id,
            'assigned_by_user_id' => auth()->id(),
            'status' => 'ASSIGNED',
            'assigned_at' => now(),
            'notes' => $this->assignNotes ?: null,
        ]);

        $responder->update(['status' => 'assigned']);

        if ($incident->status !== 'ASSIGNED' && ! $incident->isTerminal()) {
            app(IncidentStatusService::class)
                ->transition($incident, 'ASSIGNED', 'operator', auth()->user());
        }

        app(AuditLogger::class)->record('assignment.created', $assignment, null, $assignment->toArray());

        $this->reset(['assignResponderId', 'assignNotes']);
        session()->flash('message', "Assigned to {$responder->user->name}.");
    }

    public function overridePriority(): void
    {
        $incident = $this->incident();

        $this->validate([
            'overrideLevel' => ['required', 'in:LOW,MODERATE,HIGH,CRITICAL'],
            'overrideReason' => ['required', 'string', 'min:5', 'max:255'],
        ]);

        if (! Gate::allows('overridePriority', [$incident, $this->overrideLevel])) {
            $this->addError('overrideLevel', 'Lowering priority requires a barangay official.');

            return;
        }

        $before = ['priority_level' => $incident->priority_level];

        $incident->forceFill([
            'priority_level' => $this->overrideLevel,
            'priority_overridden_by' => auth()->id(),
            'priority_override_reason' => $this->overrideReason,
        ])->save();

        app(AuditLogger::class)->record('incident.priority_overridden', $incident, $before, [
            'priority_level' => $this->overrideLevel,
            'reason' => $this->overrideReason,
        ]);

        $this->reset(['overrideLevel', 'overrideReason']);
        session()->flash('message', 'Priority updated.');
    }

    public function changeStatus(string $to): void
    {
        $incident = $this->incident();
        Gate::authorize('updateStatus', $incident);

        try {
            app(IncidentStatusService::class)
                ->transition($incident, $to, 'operator', auth()->user());
            session()->flash('message', 'Status updated.');
        } catch (RuntimeException $e) {
            $this->addError('status', $e->getMessage());
        }
    }

    public function render()
    {
        $incident = $this->incident();
        $this->pageTitle = $incident->emergency_code;
        $packets = $incident->packets()->orderBy('received_at_server')->get();

        return view('livewire.incident-detail', [
            'incident' => $incident,
            'packets' => $packets,
            'packetEvents' => PacketLog::whereIn('packet_id', $packets->pluck('packet_id'))
                ->orderBy('occurred_at')->get(),
            'responders' => Responder::with('user:id,name', 'team:id,name')
                ->whereHas('user', fn ($q) => $q->where('is_active', true))
                ->get(),
            'canAssign' => Gate::allows('assign', $incident),
        ])->title($incident->emergency_code);
    }
}
