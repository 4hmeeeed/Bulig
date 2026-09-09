<?php

namespace App\Livewire;

use App\Models\EmergencyPacket;
use App\Services\EvaluationMetricsService;
use Livewire\Attributes\Layout;
use Livewire\Attributes\Title;
use Livewire\Component;
use Livewire\WithPagination;

#[Layout('layouts.app')]
#[Title('Packet Monitoring')]
class PacketMonitor extends Component
{
    use WithPagination;

    public string $status = '';

    public function render(EvaluationMetricsService $metrics)
    {
        return view('livewire.packet-monitor', [
            'metrics' => $metrics->all(),
            'packets' => EmergencyPacket::query()
                ->with(['originDevice:id,device_id,label', 'currentDevice:id,device_id,label'])
                ->when($this->status, fn ($q) => $q->where('status', $this->status))
                ->latest('received_at_server')
                ->paginate(25),
        ]);
    }
}
