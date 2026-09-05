<div class="p-6 space-y-5">
    @include('livewire.pages._header')
    <section class="panel overflow-hidden">
        <div class="table-scroll">
            <table class="data-table">
                <thead><tr><th>When</th><th>Actor</th><th>Action</th><th>Subject</th><th>Change</th><th>IP</th></tr></thead>
                <tbody>
                @forelse ($rows as $r)
                    <tr>
                        <td class="text-xs whitespace-nowrap">{{ $r->occurred_at->format('d M H:i') }}</td>
                        <td>{{ $r->user?->name ?? 'System' }}</td>
                        <td class="font-mono text-xs">{{ $r->action }}</td>
                        <td class="text-xs">{{ $r->subject_type }} #{{ $r->subject_id }}</td>
                        <td class="text-xs max-w-md">
                            @if ($r->before || $r->after)
                                <span style="color: var(--color-ink-subtle)">
                                    {{ Str::limit(json_encode($r->before), 40) }} →
                                </span>
                                {{ Str::limit(json_encode($r->after), 50) }}
                            @else — @endif
                        </td>
                        <td class="text-xs" style="color: var(--color-ink-subtle)">{{ $r->ip_address }}</td>
                    </tr>
                @empty
                    <tr><td colspan="6" class="text-center py-12" style="color: var(--color-ink-muted)">No audited actions yet.</td></tr>
                @endforelse
                </tbody>
            </table>
        </div>
        @if ($rows->hasPages())<div class="px-4 py-3 border-t" style="border-color: var(--color-border)">{{ $rows->links() }}</div>@endif
    </section>
</div>
