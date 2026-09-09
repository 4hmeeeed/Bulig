<?php

namespace App\Livewire;

use App\Models\Device;
use App\Models\Emergency;
use App\Models\EmergencyPacket;
use App\Models\SyncLog;
use Illuminate\Support\Carbon;
use Livewire\Attributes\Layout;
use Livewire\Attributes\Title;
use Livewire\Component;

#[Layout('layouts.app')]
#[Title('Dashboard')]
class Dashboard extends Component
{
    public ?string $selectedCode = null;

    public function selectIncident(string $code): void
    {
        $this->selectedCode = $this->selectedCode === $code ? null : $code;
    }

    public function render()
    {
        $incidents = Emergency::query()
            ->active()
            ->with(['type:id,code,label_en,icon', 'location', 'assignments.responder.user:id,name', 'assignments.team'])
            ->orderByUrgency()
            ->limit(25)
            ->get();

        return view('livewire.dashboard', [
            'incidents' => $incidents,
            'stats' => $this->stats(),
            'markers' => $this->markers($incidents),
            'selected' => $this->selectedCode
                ? $incidents->firstWhere('emergency_code', $this->selectedCode)
                : null,
        ]);
    }

    private function stats(): array
    {
        // "Devices seen" counts nodes heard from in the last hour, not registered
        // ones: a relay that has gone quiet is not a relay you can rely on.
        return [
            'active' => Emergency::active()->count(),
            'critical' => Emergency::active()->critical()->count(),
            'unassigned' => Emergency::active()->whereDoesntHave('assignments')->count(),
            'devices_seen' => Device::where('last_seen_at', '>=', now()->subHour())->count(),
            'multi_hop' => Emergency::where('first_hop_count', '>=', 2)->count(),
            'last_sync' => SyncLog::latest('completed_at')->value('completed_at'),
            'packets_today' => EmergencyPacket::whereDate('received_at_server', Carbon::today())->count(),
        ];
    }

    private function markers($incidents): array
    {
        return $incidents
            ->filter(fn (Emergency $e) => $e->location !== null)
            ->map(fn (Emergency $e) => [
                'code' => $e->emergency_code,
                'lat' => (float) $e->location->latitude,
                'lng' => (float) $e->location->longitude,
                'priority' => $e->priority_level,
                'type' => $e->type->label_en,
                'status' => $e->status,
                'accuracy' => $e->location->accuracy_m,
                'hops' => $e->first_hop_count,
            ])->values()->all();
    }
}
