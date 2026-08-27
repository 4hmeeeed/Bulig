<div class="p-6 space-y-5">
    @include('livewire.pages._header')
    <section class="panel overflow-hidden">
        <div class="table-scroll">
            <table class="data-table">
                <thead><tr><th>Name</th><th>Email</th><th>Role</th><th>Status</th><th>Last sign-in</th></tr></thead>
                <tbody>
                @forelse ($rows as $r)
                    <tr>
                        <td class="font-medium">{{ $r->name }}</td>
                        <td class="text-xs">{{ $r->email }}</td>
                        <td><span class="chip" style="background: var(--color-brand-soft); color: var(--color-brand-strong)">{{ $r->role }}</span></td>
                        <td>{{ $r->is_active ? 'Active' : 'Disabled' }}</td>
                        <td class="text-xs" style="color: var(--color-ink-muted)">
                            {{ $r->last_login_at?->diffForHumans() ?? 'Never' }}
                        </td>
                    </tr>
                @empty
                    <tr><td colspan="5" class="text-center py-12" style="color: var(--color-ink-muted)">No users.</td></tr>
                @endforelse
                </tbody>
            </table>
        </div>
        @if ($rows->hasPages())<div class="px-4 py-3 border-t" style="border-color: var(--color-border)">{{ $rows->links() }}</div>@endif
    </section>
</div>
