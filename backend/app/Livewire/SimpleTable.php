<?php

namespace App\Livewire;

use App\Models\AuditLog;
use App\Models\Emergency;
use App\Models\Responder;
use App\Models\RescueTeam;
use App\Models\Setting;
use App\Models\SyncLog;
use App\Models\User;
use Livewire\Attributes\Layout;
use Livewire\Component;
use Livewire\WithPagination;

/**
 * Backs the reference and log pages, which are all "filter a table" screens.
 *
 * Kept as one component rather than seven near-identical ones: the pages differ
 * only in their query and their columns, and duplicating the pagination and
 * search plumbing seven times would be harder to keep correct, not easier.
 */
#[Layout('layouts.app')]
class SimpleTable extends Component
{
    use WithPagination;

    public string $page_key;
    public string $q = '';

    public function mount(string $page_key): void
    {
        $this->page_key = $page_key;
    }

    public function updatedQ(): void
    {
        $this->resetPage();
    }

    public function render()
    {
        [$title, $description, $rows, $view] = match ($this->page_key) {
            'responders' => [
                'Responders',
                'Personnel available for assignment.',
                Responder::with('user:id,name,email', 'team:id,name')
                    ->when($this->q, fn ($query) => $query->whereHas(
                        'user', fn ($u) => $u->where('name', 'like', "%{$this->q}%")
                    ))
                    ->paginate(20),
                'responders',
            ],
            'teams' => [
                'Rescue teams',
                'Barangay response units.',
                RescueTeam::withCount('responders')
                    ->when($this->q, fn ($query) => $query->where('name', 'like', "%{$this->q}%"))
                    ->paginate(20),
                'teams',
            ],
            'sync-logs' => [
                'Synchronisation logs',
                'Every batch a device has pushed to the server.',
                SyncLog::with('device:id,device_id,label')->latest('started_at')->paginate(25),
                'sync-logs',
            ],
            'audit-logs' => [
                'Audit logs',
                'Administrative and coordination actions. Append-only.',
                AuditLog::with('user:id,name')
                    ->when($this->q, fn ($query) => $query->where('action', 'like', "%{$this->q}%"))
                    ->latest('occurred_at')->paginate(25),
                'audit-logs',
            ],
            'users' => [
                'Users',
                'Accounts with access to Bulig.',
                User::when($this->q, fn ($query) => $query->where('name', 'like', "%{$this->q}%")
                    ->orWhere('email', 'like', "%{$this->q}%"))
                    ->orderBy('role')->paginate(25),
                'users',
            ],
            'settings' => [
                'Settings',
                'Runtime configuration, including the priority scoring formula.',
                Setting::orderBy('group')->orderBy('key')->paginate(50),
                'settings',
            ],
            'map' => [
                'Emergency map',
                'All active incidents, coloured by priority.',
                Emergency::active()->with(['location', 'type:id,code,label_en'])->get(),
                'map',
            ],
        };

        return view("livewire.pages.{$view}", compact('title', 'description', 'rows'))
            ->title($title);
    }
}
