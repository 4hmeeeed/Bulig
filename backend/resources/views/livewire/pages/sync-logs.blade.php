<div class="p-6 space-y-5">
    @php $searchable = false; @endphp
    @include('livewire.pages._header')
    <section class="panel overflow-hidden">
        <div class="table-scroll">
            <table class="data-table">
                <thead><tr>
                    <th>Device</th><th>Direction</th><th>Sent</th><th>Accepted</th>
                    <th>Duplicate</th><th>Rejected</th><th>Duration</th><th>Outcome</th><th>Started</th>
                </tr></thead>
                <tbody>
                @forelse ($rows as $r)
                    <tr>
                        <td>{{ $r->device?->displayName() ?? '—' }}</td>
                        <td class="text-xs">{{ $r->direction }}</td>
                        <td class="tabular-nums">{{ $r->packets_sent }}</td>
                        <td class="tabular-nums">{{ $r->packets_accepted }}</td>
                        <td class="tabular-nums">{{ $r->packets_duplicate }}</td>
                        <td class="tabular-nums">{{ $r->packets_rejected }}</td>
                        <td class="tabular-nums text-xs">{{ $r->duration_ms ? $r->duration_ms.' ms' : '—' }}</td>
                        <td>
                            <span style="color: {{ $r->outcome === 'success'
                                ? 'var(--color-state-online)' : 'var(--color-state-danger)' }}">
                                {{ $r->outcome }}
                            </span>
                        </td>
                        <td class="text-xs whitespace-nowrap" style="color: var(--color-ink-muted)">
                            {{ $r->started_at?->diffForHumans() }}
                        </td>
                    </tr>
                @empty
                    <tr><td colspan="9" class="text-center py-12" style="color: var(--color-ink-muted)">No synchronisations recorded.</td></tr>
                @endforelse
                </tbody>
            </table>
        </div>
        @if ($rows->hasPages())<div class="px-4 py-3 border-t" style="border-color: var(--color-border)">{{ $rows->links() }}</div>@endif
    </section>
</div>
