<div class="p-6 space-y-5">
    <header>
        <h1 class="text-2xl font-bold tracking-tight">Incident queue</h1>
        <p class="text-sm" style="color: var(--color-ink-muted)">
            Sorted by priority, then by time received.
        </p>
    </header>

    <section class="panel p-4 grid gap-3 md:grid-cols-5" aria-label="Filters">
        <div class="md:col-span-2">
            <label class="field-label" for="f-q">Search</label>
            <input id="f-q" wire:model.live.debounce.400ms="q" type="search"
                   placeholder="Code or description"
                   class="w-full rounded-lg border px-3 py-2 text-sm"
                   style="border-color: var(--color-border-strong)">
        </div>
        <div>
            <label class="field-label" for="f-priority">Priority</label>
            <select id="f-priority" wire:model.live="priority"
                    class="w-full rounded-lg border px-3 py-2 text-sm"
                    style="border-color: var(--color-border-strong)">
                <option value="">All</option>
                @foreach (['CRITICAL', 'HIGH', 'MODERATE', 'LOW'] as $level)
                    <option value="{{ $level }}">{{ $level }}</option>
                @endforeach
            </select>
        </div>
        <div>
            <label class="field-label" for="f-status">Status</label>
            <select id="f-status" wire:model.live="status"
                    class="w-full rounded-lg border px-3 py-2 text-sm"
                    style="border-color: var(--color-border-strong)">
                <option value="">All</option>
                @foreach (['NEW','TRIAGED','ASSIGNED','EN_ROUTE','ON_SITE','RESOLVED','CANCELLED'] as $s)
                    <option value="{{ $s }}">{{ str_replace('_', ' ', $s) }}</option>
                @endforeach
            </select>
        </div>
        <div>
            <label class="field-label" for="f-type">Type</label>
            <select id="f-type" wire:model.live="type"
                    class="w-full rounded-lg border px-3 py-2 text-sm"
                    style="border-color: var(--color-border-strong)">
                <option value="">All</option>
                @foreach ($types as $t)
                    <option value="{{ $t->code }}">{{ $t->label_en }}</option>
                @endforeach
            </select>
        </div>
        <div class="md:col-span-5 flex items-center gap-4">
            <label class="flex items-center gap-2 text-sm">
                <input type="checkbox" wire:model.live="activeOnly">
                Active incidents only
            </label>
            <button wire:click="clearFilters" class="text-sm underline" style="color: var(--color-brand)">
                Clear filters
            </button>
        </div>
    </section>

    <section class="panel overflow-hidden">
        <div class="table-scroll">
            <table class="data-table">
                <thead>
                    <tr>
                        <th>Priority</th>
                        <th>Code</th>
                        <th>Type</th>
                        <th>Description</th>
                        <th>Affected</th>
                        <th>Route</th>
                        <th>Status</th>
                        <th>Assigned to</th>
                        <th>Received</th>
                        <th><span class="sr-only">Actions</span></th>
                    </tr>
                </thead>
                <tbody>
                @forelse ($incidents as $incident)
                    @php $assignment = $incident->assignments->first(); @endphp
                    <tr>
                        <td><x-priority-chip :level="$incident->priority_level" /></td>
                        <td class="font-mono text-xs whitespace-nowrap">{{ $incident->emergency_code }}</td>
                        <td class="whitespace-nowrap">{{ $incident->type->label_en }}</td>
                        <td class="max-w-xs"><span class="line-clamp-2">{{ $incident->description }}</span></td>
                        <td class="tabular-nums">{{ $incident->affected_count }}</td>
                        <td class="whitespace-nowrap text-xs" style="color: var(--color-ink-muted)">
                            {{ $incident->first_hop_count }} hop{{ $incident->first_hop_count === 1 ? '' : 's' }}
                        </td>
                        <td><x-status-chip :status="$incident->status" /></td>
                        <td class="whitespace-nowrap">
                            {{ $assignment?->responder?->user->name ?? '—' }}
                        </td>
                        <td class="whitespace-nowrap text-xs" style="color: var(--color-ink-muted)">
                            {{ $incident->received_at_server?->diffForHumans() }}
                        </td>
                        <td>
                            <a href="{{ route('incidents.show', $incident->emergency_code) }}"
                               class="underline text-sm" style="color: var(--color-brand)">Open</a>
                        </td>
                    </tr>
                @empty
                    <tr>
                        <td colspan="10" class="text-center py-12" style="color: var(--color-ink-muted)">
                            No incidents match these filters.
                        </td>
                    </tr>
                @endforelse
                </tbody>
            </table>
        </div>

        @if ($incidents->hasPages())
            <div class="px-4 py-3 border-t" style="border-color: var(--color-border)">
                {{ $incidents->links() }}
            </div>
        @endif
    </section>
</div>
