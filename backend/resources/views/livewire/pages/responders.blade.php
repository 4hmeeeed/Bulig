<div class="p-6 space-y-5">
    @include('livewire.pages._header')
    <section class="panel overflow-hidden">
        <div class="table-scroll">
            <table class="data-table">
                <thead><tr><th>Name</th><th>Team</th><th>Badge</th><th>Specialisation</th><th>Status</th></tr></thead>
                <tbody>
                @forelse ($rows as $r)
                    <tr>
                        <td>
                            <div class="font-medium">{{ $r->user->name }}</div>
                            <div class="text-xs" style="color: var(--color-ink-subtle)">{{ $r->user->email }}</div>
                        </td>
                        <td>{{ $r->team?->name ?? '—' }}</td>
                        <td class="font-mono text-xs">{{ $r->badge_no ?? '—' }}</td>
                        <td>{{ $r->specialisation ?? '—' }}</td>
                        <td>
                            <span class="chip" style="border: 1px solid {{ $r->status === 'available'
                                ? 'var(--color-state-online)' : 'var(--color-state-syncing)' }};
                                color: {{ $r->status === 'available'
                                ? 'var(--color-state-online)' : 'var(--color-state-syncing)' }}">
                                {{ $r->status }}
                            </span>
                        </td>
                    </tr>
                @empty
                    <tr><td colspan="5" class="text-center py-12" style="color: var(--color-ink-muted)">No responders.</td></tr>
                @endforelse
                </tbody>
            </table>
        </div>
        @if ($rows->hasPages())<div class="px-4 py-3 border-t" style="border-color: var(--color-border)">{{ $rows->links() }}</div>@endif
    </section>
</div>
