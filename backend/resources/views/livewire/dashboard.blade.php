<div class="p-6 space-y-5" wire:poll.10s>

    <header class="flex items-start justify-between gap-4 flex-wrap">
        <div>
            <h1 class="text-2xl font-bold tracking-tight">Dashboard</h1>
            <p class="text-sm" style="color: var(--color-ink-muted)">
                Live emergency coordination for the pilot barangay.
            </p>
        </div>
        <div class="text-right text-xs" style="color: var(--color-ink-subtle)">
            <div>Last synchronisation</div>
            <div class="font-semibold" style="color: var(--color-ink)">
                {{ $stats['last_sync']?->diffForHumans() ?? 'No syncs recorded' }}
            </div>
        </div>
    </header>

    <section class="grid gap-3 grid-cols-2 lg:grid-cols-5" aria-label="System status">
        <div class="stat-tile">
            <span class="stat-value">{{ $stats['active'] }}</span>
            <span class="stat-label">Active incidents</span>
        </div>
        <div class="stat-tile" style="border-color: var(--color-priority-critical)">
            <span class="stat-value" style="color: var(--color-priority-critical)">{{ $stats['critical'] }}</span>
            <span class="stat-label">Critical</span>
        </div>
        <div class="stat-tile">
            <span class="stat-value">{{ $stats['unassigned'] }}</span>
            <span class="stat-label">Awaiting assignment</span>
        </div>
        <div class="stat-tile">
            <span class="stat-value">{{ $stats['devices_seen'] }}</span>
            <span class="stat-label">Devices seen (1h)</span>
        </div>
        <div class="stat-tile">
            <span class="stat-value">{{ $stats['multi_hop'] }}</span>
            <span class="stat-label">Arrived multi-hop</span>
        </div>
    </section>

    <div class="grid gap-5 lg:grid-cols-2 items-start">

        <section class="panel overflow-hidden" aria-label="Incident queue">
            <div class="px-5 py-3.5 border-b flex items-center justify-between"
                 style="border-color: var(--color-border)">
                <h2 class="font-semibold">Incident queue</h2>
                <a href="{{ route('incidents') }}" class="text-sm underline"
                   style="color: var(--color-brand)">View all</a>
            </div>

            <ul class="divide-y max-h-[34rem] overflow-y-auto" style="border-color: var(--color-border)">
                @forelse ($incidents as $incident)
                    @php $assignment = $incident->assignments->first(); @endphp
                    <li>
                        <button type="button"
                                wire:click="selectIncident('{{ $incident->emergency_code }}')"
                                class="w-full text-left px-5 py-3.5 hover:bg-[var(--color-surface-raised)]"
                                @if($selected?->emergency_code === $incident->emergency_code)
                                    style="background: var(--color-brand-soft)"
                                @endif>
                            <div class="flex items-center gap-2 flex-wrap">
                                <x-priority-chip :level="$incident->priority_level" />
                                <span class="font-mono text-xs" style="color: var(--color-ink-subtle)">
                                    {{ $incident->emergency_code }}
                                </span>
                                <x-status-chip :status="$incident->status" />
                                @if ($incident->first_hop_count >= 2)
                                    <span class="chip" style="background: var(--color-brand-soft); color: var(--color-brand-strong)"
                                          title="Reached the server after {{ $incident->first_hop_count }} relay hops">
                                        {{ $incident->first_hop_count }} hops
                                    </span>
                                @endif
                            </div>
                            <div class="mt-1.5 font-medium">{{ $incident->type->label_en }}</div>
                            <div class="text-sm line-clamp-1" style="color: var(--color-ink-muted)">
                                {{ $incident->description }}
                            </div>
                            <div class="mt-1 text-xs" style="color: var(--color-ink-subtle)">
                                {{ $incident->affected_count }} affected ·
                                {{ $incident->received_at_server?->diffForHumans() }} ·
                                {{ $assignment?->responder?->user->name ?? 'Unassigned' }}
                            </div>
                        </button>
                    </li>
                @empty
                    <li class="px-5 py-12 text-center" style="color: var(--color-ink-muted)">
                        No active emergencies.
                    </li>
                @endforelse
            </ul>
        </section>

        <section class="space-y-5">
            <div class="panel overflow-hidden" aria-label="Emergency map">
                <div class="px-5 py-3.5 border-b" style="border-color: var(--color-border)">
                    <h2 class="font-semibold">Emergency map</h2>
                </div>
                <div wire:ignore
                     id="dashboard-map"
                     class="h-[22rem] w-full"
                     data-markers="{{ json_encode($markers) }}"
                     data-center="{{ json_encode(\App\Models\Setting::get('map_default_center', ['lat' => 11.2444, 'lng' => 125.0048])) }}"
                     data-zoom="{{ \App\Models\Setting::get('map_default_zoom', 15) }}"></div>
            </div>

            @if ($selected)
                <div class="panel p-5 space-y-4">
                    <div class="flex items-start justify-between gap-3">
                        <div>
                            <div class="font-mono text-xs" style="color: var(--color-ink-subtle)">
                                {{ $selected->emergency_code }}
                            </div>
                            <h3 class="text-lg font-semibold">{{ $selected->type->label_en }}</h3>
                        </div>
                        <x-priority-chip :level="$selected->priority_level" />
                    </div>

                    <p class="text-sm">{{ $selected->description }}</p>

                    <dl class="grid grid-cols-2 gap-3 text-sm">
                        <div><dt class="field-label">Affected</dt><dd>{{ $selected->affected_count }}</dd></div>
                        <div><dt class="field-label">Children</dt><dd>{{ $selected->children_count }}</dd></div>
                        <div><dt class="field-label">Elderly</dt><dd>{{ $selected->elderly_count }}</dd></div>
                        <div><dt class="field-label">Mobility limited</dt><dd>{{ $selected->mobility_limited_count }}</dd></div>
                    </dl>

                    {{-- The rule trace. This is what makes a rule-based priority
                         defensible: an operator can see the reasoning, not a score. --}}
                    @if ($selected->priority_breakdown)
                        <div>
                            <div class="field-label">Why this priority</div>
                            <ul class="text-sm space-y-0.5">
                                @foreach ($selected->priority_breakdown['factors'] ?? [] as $factor)
                                    <li class="flex justify-between gap-3">
                                        <span style="color: var(--color-ink-muted)">
                                            {{ str_replace('_', ' ', ucfirst($factor['rule'])) }}
                                            <span style="color: var(--color-ink-subtle)">— {{ $factor['detail'] }}</span>
                                        </span>
                                        <span class="font-mono tabular-nums">+{{ $factor['points'] }}</span>
                                    </li>
                                @endforeach
                                <li class="flex justify-between gap-3 pt-1.5 mt-1.5 border-t font-semibold"
                                    style="border-color: var(--color-border)">
                                    <span>Total</span>
                                    <span class="font-mono tabular-nums">{{ $selected->priority_score }}</span>
                                </li>
                            </ul>
                        </div>
                    @endif

                    <a href="{{ route('incidents.show', $selected->emergency_code) }}" class="btn btn-primary w-full">
                        Open incident
                    </a>
                </div>
            @endif
        </section>
    </div>

    @script
    <script>
        // Rebuilt on every Livewire render so markers track the polled data.
        const el = document.getElementById('dashboard-map');
        if (el && !el._map) {
            const center = JSON.parse(el.dataset.center);
            el._map = L.map(el).setView([center.lat, center.lng], Number(el.dataset.zoom));
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                attribution: '&copy; OpenStreetMap contributors',
                maxZoom: 19,
            }).addTo(el._map);
            el._layer = L.layerGroup().addTo(el._map);
        }

        const colours = {
            CRITICAL: getComputedStyle(document.body).getPropertyValue('--color-priority-critical'),
            HIGH: getComputedStyle(document.body).getPropertyValue('--color-priority-high'),
            MODERATE: getComputedStyle(document.body).getPropertyValue('--color-priority-moderate'),
            LOW: getComputedStyle(document.body).getPropertyValue('--color-priority-low'),
        };

        const draw = () => {
            if (!el?._layer) return;
            el._layer.clearLayers();
            JSON.parse(el.dataset.markers).forEach((m) => {
                L.circleMarker([m.lat, m.lng], {
                    radius: m.priority === 'CRITICAL' ? 11 : 8,
                    color: '#fff',
                    weight: 2,
                    fillColor: colours[m.priority] ?? colours.LOW,
                    fillOpacity: 0.95,
                }).bindPopup(
                    `<strong>${m.code}</strong><br>${m.type}<br>` +
                    `${m.priority} &middot; ${m.status.replace('_', ' ')}<br>` +
                    `<small>Accuracy ${m.accuracy ?? '?'} m &middot; ${m.hops} hop(s)</small>`
                ).addTo(el._layer);
            });
        };

        draw();
        Livewire.hook('morph.updated', draw);
    </script>
    @endscript
</div>
