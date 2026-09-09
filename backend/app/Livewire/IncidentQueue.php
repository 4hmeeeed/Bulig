<?php

namespace App\Livewire;

use App\Models\Emergency;
use App\Models\EmergencyType;
use Livewire\Attributes\Layout;
use Livewire\Attributes\Title;
use Livewire\Attributes\Url;
use Livewire\Component;
use Livewire\WithPagination;

#[Layout('layouts.app')]
#[Title('Incident Queue')]
class IncidentQueue extends Component
{
    use WithPagination;

    #[Url] public string $status = '';
    #[Url] public string $priority = '';
    #[Url] public string $type = '';
    #[Url] public string $q = '';
    #[Url] public bool $activeOnly = true;

    public function updated(): void
    {
        $this->resetPage();
    }

    public function clearFilters(): void
    {
        $this->reset(['status', 'priority', 'type', 'q']);
        $this->activeOnly = true;
        $this->resetPage();
    }

    public function render()
    {
        $incidents = Emergency::query()
            ->with(['type:id,code,label_en', 'assignments.responder.user:id,name'])
            ->when($this->activeOnly, fn ($q) => $q->active())
            ->when($this->status, fn ($q) => $q->where('status', $this->status))
            ->when($this->priority, fn ($q) => $q->where('priority_level', $this->priority))
            ->when($this->type, fn ($q) => $q->whereRelation('type', 'code', $this->type))
            ->when($this->q, fn ($q) => $q->where(fn ($w) => $w
                ->where('emergency_code', 'like', "%{$this->q}%")
                ->orWhere('description', 'like', "%{$this->q}%")))
            ->orderByUrgency()
            ->paginate(20);

        return view('livewire.incident-queue', [
            'incidents' => $incidents,
            'types' => EmergencyType::where('is_active', true)->orderBy('sort_order')->get(),
        ]);
    }
}
