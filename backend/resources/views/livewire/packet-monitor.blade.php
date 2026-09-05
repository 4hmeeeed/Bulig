<div class="p-6 space-y-5" wire:poll.15s>
    <header>
        <h1 class="text-2xl font-bold tracking-tight">Packet monitoring</h1>
        <p class="text-sm" style="color: var(--color-ink-muted)">
            Relay and synchronisation behaviour of the device mesh. These figures are the
            operational metrics reported in the system evaluation.
        </p>
    </header>

    <section class="grid gap-3 grid-cols-2 lg:grid-cols-4" aria-label="Mesh metrics">
        <div class="stat-tile">
            <span class="stat-value">{{ $metrics['delivery']['multi_hop'] }}</span>
            <span class="stat-label">Multi-hop deliveries</span>
        </div>
        <div class="stat-tile">
            <span class="stat-value">{{ $metrics['duplicate_suppression']['suppressed'] }}</span>
            <span class="stat-label">Duplicates suppressed</span>
        </div>
        <div class="stat-tile">
            <span class="stat-value">{{ $metrics['ttl_enforcement']['ttl_expired_packets'] }}</span>
            <span class="stat-label">TTL-expired packets</span>
        </div>
        <div class="stat-tile" @if($metrics['ttl_enforcement']['ttl_violations'] > 0) style="border-color: var(--color-state-danger)" @endif>
            <span class="stat-value"
                  @if($metrics['ttl_enforcement']['ttl_violations'] > 0) style="color: var(--color-state-danger)" @endif>
                {{ $metrics['ttl_enforcement']['ttl_violations'] }}
            </span>
            <span class="stat-label">TTL violations</span>
        </div>
    </section>

    <div class="grid gap-5 lg:grid-cols-3 items-start">

        <section class="panel p-5">
            <h2 class="font-semibold mb-3">Transmission delay</h2>
            <p class="text-xs mb-3" style="color: var(--color-ink-muted)">
                Corrected for device clock drift. Reports whose corrected delay is impossible
                are excluded rather than averaged in.
            </p>
            <dl class="space-y-2 text-sm">
                @foreach ([
                    'Samples' => $metrics['transmission_delay_ms']['n'],
                    'Median' => $metrics['transmission_delay_ms']['median'],
                    '90th percentile' => $metrics['transmission_delay_ms']['p90'],
                    'Maximum' => $metrics['transmission_delay_ms']['max'],
                ] as $label => $value)
                    <div class="flex justify-between">
                        <dt style="color: var(--color-ink-muted)">{{ $label }}</dt>
                        <dd class="font-mono tabular-nums">
                            {{ $label === 'Samples' ? $value : ($value !== null ? round($value / 1000, 1).' s' : '—') }}
                        </dd>
                    </div>
                @endforeach
                <div class="flex justify-between pt-2 border-t" style="border-color: var(--color-border)">
                    <dt style="color: var(--color-ink-muted)">Excluded (clock anomaly)</dt>
                    <dd class="font-mono tabular-nums">{{ $metrics['transmission_delay_ms']['excluded_clock_anomaly'] }}</dd>
                </div>
            </dl>
        </section>

        <section class="panel p-5">
            <h2 class="font-semibold mb-3">Delivery by hop count</h2>
            @php $byHop = collect($metrics['delivery']['by_hop_count']); $max = max(1, $byHop->max() ?? 1); @endphp
            <ul class="space-y-2 text-sm">
                @forelse ($byHop as $hop => $count)
                    <li>
                        <div class="flex justify-between mb-1">
                            <span style="color: var(--color-ink-muted)">
                                {{ $hop == 0 ? 'Direct (no relay)' : $hop.' hop'.($hop == 1 ? '' : 's') }}
                            </span>
                            <span class="font-mono tabular-nums">{{ $count }}</span>
                        </div>
                        <div class="h-1.5 rounded-full" style="background: var(--color-border)">
                            <div class="h-full rounded-full"
                                 style="width: {{ round($count / $max * 100) }}%; background: var(--color-brand)"></div>
                        </div>
                    </li>
                @empty
                    <li style="color: var(--color-ink-muted)">No packets recorded.</li>
                @endforelse
            </ul>
        </section>

        <section class="panel p-5">
            <h2 class="font-semibold mb-3">Integrity &amp; synchronisation</h2>
            <dl class="space-y-2 text-sm">
                @foreach ([
                    'Signatures verified' => $metrics['integrity']['hmac_verified'],
                    'Signature failures' => $metrics['integrity']['hmac_failed'],
                    'Unregistered origin' => $metrics['integrity']['hmac_unverifiable'],
                    'Sync batches' => $metrics['synchronisation']['total_batches'],
                ] as $label => $value)
                    <div class="flex justify-between">
                        <dt style="color: var(--color-ink-muted)">{{ $label }}</dt>
                        <dd class="font-mono tabular-nums">{{ $value }}</dd>
                    </div>
                @endforeach
                <div class="flex justify-between pt-2 border-t" style="border-color: var(--color-border)">
                    <dt style="color: var(--color-ink-muted)">Sync success rate</dt>
                    <dd class="font-mono tabular-nums">
                        {{ $metrics['synchronisation']['success_rate'] !== null
                            ? round($metrics['synchronisation']['success_rate'] * 100, 1).'%' : '—' }}
                    </dd>
                </div>
            </dl>
        </section>
    </div>

    <section class="panel overflow-hidden">
        <div class="px-5 py-3.5 border-b flex items-center gap-3" style="border-color: var(--color-border)">
            <h2 class="font-semibold">Packets</h2>
            <select wire:model.live="status" aria-label="Filter by packet status"
                    class="ml-auto rounded-lg border px-3 py-1.5 text-sm"
                    style="border-color: var(--color-border-strong)">
                <option value="">All statuses</option>
                @foreach (['ACCEPTED','DUPLICATE','TTL_EXPIRED','INVALID_HMAC','REJECTED'] as $s)
                    <option value="{{ $s }}">{{ str_replace('_', ' ', $s) }}</option>
                @endforeach
            </select>
        </div>
        <div class="table-scroll">
            <table class="data-table">
                <thead>
                    <tr>
                        <th>Packet</th><th>Emergency</th><th>Origin</th><th>Delivered by</th>
                        <th>Hops</th><th>TTL</th><th>Signature</th><th>Status</th><th>Received</th>
                    </tr>
                </thead>
                <tbody>
                @forelse ($packets as $packet)
                    <tr>
                        <td class="font-mono text-xs">{{ Str::limit($packet->packet_id, 13) }}</td>
                        <td class="font-mono text-xs">{{ Str::limit($packet->emergency_uuid, 13) }}</td>
                        <td class="text-xs">{{ $packet->originDevice?->displayName() ?? '—' }}</td>
                        <td class="text-xs">{{ $packet->currentDevice?->displayName() ?? '—' }}</td>
                        <td class="tabular-nums">{{ $packet->hop_count }}</td>
                        <td class="tabular-nums text-xs">{{ $packet->ttl_remaining }}/{{ $packet->ttl_initial }}</td>
                        <td class="text-xs">
                            @if ($packet->hmac_valid === true)
                                <span style="color: var(--color-state-online)">Verified</span>
                            @elseif ($packet->hmac_valid === false)
                                <span style="color: var(--color-state-danger)">Failed</span>
                            @else
                                <span style="color: var(--color-ink-subtle)">—</span>
                            @endif
                        </td>
                        <td class="text-xs">{{ str_replace('_', ' ', $packet->status) }}</td>
                        <td class="text-xs whitespace-nowrap" style="color: var(--color-ink-muted)">
                            {{ $packet->received_at_server?->diffForHumans() }}
                        </td>
                    </tr>
                @empty
                    <tr><td colspan="9" class="text-center py-12" style="color: var(--color-ink-muted)">
                        No packets recorded yet.
                    </td></tr>
                @endforelse
                </tbody>
            </table>
        </div>
        @if ($packets->hasPages())
            <div class="px-4 py-3 border-t" style="border-color: var(--color-border)">{{ $packets->links() }}</div>
        @endif
    </section>
</div>
