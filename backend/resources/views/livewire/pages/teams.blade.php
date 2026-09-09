<div class="p-6 space-y-5">
    @include('livewire.pages._header')
    <section class="panel overflow-hidden">
        <div class="table-scroll">
            <table class="data-table">
                <thead><tr><th>Team</th><th>Code</th><th>Base</th><th>Members</th><th>Contact</th><th>Status</th></tr></thead>
                <tbody>
                @forelse ($rows as $r)
                    <tr>
                        <td class="font-medium">{{ $r->name }}</td>
                        <td class="font-mono text-xs">{{ $r->code }}</td>
                        <td>{{ $r->base_location ?? '—' }}</td>
                        <td class="tabular-nums">{{ $r->responders_count }}</td>
                        <td>{{ $r->contact_number ?? '—' }}</td>
                        <td>{{ $r->is_active ? 'Active' : 'Inactive' }}</td>
                    </tr>
                @empty
                    <tr><td colspan="6" class="text-center py-12" style="color: var(--color-ink-muted)">No teams.</td></tr>
                @endforelse
                </tbody>
            </table>
        </div>
        @if ($rows->hasPages())<div class="px-4 py-3 border-t" style="border-color: var(--color-border)">{{ $rows->links() }}</div>@endif
    </section>
</div>
