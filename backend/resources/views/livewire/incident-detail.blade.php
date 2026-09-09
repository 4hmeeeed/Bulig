<div class="p-6 space-y-5">

    @if (session('message'))
        <div class="panel px-4 py-3 text-sm"
             style="border-color: var(--color-state-online); color: var(--color-state-online)">
            {{ session('message') }}
        </div>
    @endif

    <header class="flex items-start justify-between gap-4 flex-wrap">
        <div>
            <a href="{{ route('incidents') }}" class="text-sm underline" style="color: var(--color-brand)">
                &larr; Incident queue
            </a>
            <div class="mt-1 flex items-center gap-3 flex-wrap">
                <h1 class="text-2xl font-bold tracking-tight">{{ $incident->type->label_en }}</h1>
                <x-priority-chip :level="$incident->priority_level" />
                <x-status-chip :status="$incident->status" />
            </div>
            <div class="font-mono text-sm mt-1" style="color: var(--color-ink-subtle)">
                {{ $incident->emergency_code }}
            </div>
        </div>
    </header>

    <div class="grid gap-5 lg:grid-cols-3 items-start">

        <div class="lg:col-span-2 space-y-5">

            <section class="panel p-5 space-y-4">
                <h2 class="font-semibold">Report</h2>
                <p>{{ $incident->description ?: 'No description provided.' }}</p>

                <dl class="grid gap-4 sm:grid-cols-4 text-sm">
                    <div><dt class="field-label">Affected</dt><dd class="text-lg font-semibold tabular-nums">{{ $incident->affected_count }}</dd></div>
                    <div><dt class="field-label">Children</dt><dd class="text-lg font-semibold tabular-nums">{{ $incident->children_count }}</dd></div>
                    <div><dt class="field-label">Elderly</dt><dd class="text-lg font-semibold tabular-nums">{{ $incident->elderly_count }}</dd></div>
                    <div><dt class="field-label">Mobility limited</dt><dd class="text-lg font-semibold tabular-nums">{{ $incident->mobility_limited_count }}</dd></div>
                </dl>

                @if ($incident->is_life_threatening)
                    <div class="chip" style="background: var(--color-priority-critical-soft); color: var(--color-priority-critical)">
                        Reporter flagged this as life-threatening
                    </div>
                @endif

                @if ($incident->vulnerability_notes)
                    <div>
                        <div class="field-label">Vulnerability notes</div>
                        <p class="text-sm">{{ $incident->vulnerability_notes }}</p>
                    </div>
                @endif

                <dl class="grid gap-4 sm:grid-cols-3 text-sm pt-2 border-t" style="border-color: var(--color-border)">
                    <div>
                        <dt class="field-label">Reported (device clock)</dt>
                        <dd>{{ $incident->created_at_device?->format('d M Y, H:i') ?? '—' }}</dd>
                    </div>
                    <div>
                        <dt class="field-label">Received (server)</dt>
                        <dd>{{ $incident->received_at_server?->format('d M Y, H:i') ?? '—' }}</dd>
                    </div>
                    <div>
                        <dt class="field-label">Location accuracy</dt>
                        <dd>{{ $incident->location?->accuracy_m ? round($incident->location->accuracy_m).' m' : 'Unknown' }}</dd>
                    </div>
                </dl>

                @if ($incident->clock_anomaly)
                    <p class="text-xs" style="color: var(--color-state-offline)">
                        The originating device's clock could not be reconciled with server time.
                        Timing figures for this report are excluded from evaluation statistics.
                    </p>
                @endif
            </section>

            {{-- Routing evidence: the record that this report escaped an outage by
                 riding on other people's phones. Unique to this system. --}}
            <section class="panel overflow-hidden">
                <div class="px-5 py-3.5 border-b" style="border-color: var(--color-border)">
                    <h2 class="font-semibold">Delivery route</h2>
                    <p class="text-xs" style="color: var(--color-ink-muted)">
                        How this report reached the command center.
                    </p>
                </div>
                <div class="table-scroll">
                    <table class="data-table">
                        <thead>
                            <tr>
                                <th>Packet</th><th>Hops</th><th>TTL left</th>
                                <th>Signature</th><th>Delay</th><th>Status</th>
                            </tr>
                        </thead>
                        <tbody>
                        @forelse ($packets as $packet)
                            <tr>
                                <td class="font-mono text-xs">{{ Str::limit($packet->packet_id, 13) }}</td>
                                <td class="tabular-nums">{{ $packet->hop_count }}</td>
                                <td class="tabular-nums">{{ $packet->ttl_remaining }} / {{ $packet->ttl_initial }}</td>
                                <td>
                                    @if ($packet->hmac_valid === true)
                                        <span style="color: var(--color-state-online)">Verified</span>
                                    @elseif ($packet->hmac_valid === false)
                                        <span style="color: var(--color-state-danger)">Failed</span>
                                    @else
                                        <span style="color: var(--color-ink-subtle)">Unregistered device</span>
                                    @endif
                                </td>
                                <td class="tabular-nums text-xs">
                                    @php $delay = $packet->correctedDelayMs(); @endphp
                                    {{ $delay !== null && $delay >= 0 ? round($delay / 1000).' s' : '—' }}
                                </td>
                                <td class="text-xs">{{ str_replace('_', ' ', $packet->status) }}</td>
                            </tr>
                        @empty
                            <tr><td colspan="6" class="text-center py-8" style="color: var(--color-ink-muted)">
                                No packet records.
                            </td></tr>
                        @endforelse
                        </tbody>
                    </table>
                </div>
            </section>

            <section class="panel p-5">
                <h2 class="font-semibold mb-4">Timeline</h2>
                <ol class="space-y-3 text-sm">
                    @foreach ($packetEvents as $event)
                        <li class="flex gap-3">
                            <span class="font-mono text-xs whitespace-nowrap pt-0.5" style="color: var(--color-ink-subtle)">
                                {{ $event->occurred_at->format('H:i:s') }}
                            </span>
                            <span>
                                {{ str_replace('_', ' ', ucfirst(strtolower($event->event))) }}
                                <span style="color: var(--color-ink-subtle)">
                                    (hop {{ $event->hop_count }}, TTL {{ $event->ttl_remaining }})
                                </span>
                            </span>
                        </li>
                    @endforeach
                    @foreach ($incident->statusHistory as $history)
                        <li class="flex gap-3">
                            <span class="font-mono text-xs whitespace-nowrap pt-0.5" style="color: var(--color-ink-subtle)">
                                {{ $history->occurred_at->format('H:i:s') }}
                            </span>
                            <span>
                                <strong>{{ str_replace('_', ' ', $history->to_status) }}</strong>
                                @if ($history->changedBy) by {{ $history->changedBy->name }} @endif
                                @if ($history->note)
                                    <span style="color: var(--color-ink-subtle)">— {{ $history->note }}</span>
                                @endif
                            </span>
                        </li>
                    @endforeach
                </ol>
            </section>
        </div>

        <div class="space-y-5">

            @if ($incident->location)
                <section class="panel overflow-hidden">
                    <div wire:ignore id="incident-map" class="h-56 w-full"
                         data-lat="{{ $incident->location->latitude }}"
                         data-lng="{{ $incident->location->longitude }}"
                         data-accuracy="{{ $incident->location->accuracy_m ?? 0 }}"
                         data-priority="{{ $incident->priority_level }}"></div>
                    <div class="px-4 py-3 text-xs" style="color: var(--color-ink-muted)">
                        {{ number_format($incident->location->latitude, 5) }},
                        {{ number_format($incident->location->longitude, 5) }}
                        · {{ $incident->location->provider }}
                    </div>
                </section>
            @endif

            <section class="panel p-5 space-y-3">
                <h2 class="font-semibold">Why this priority</h2>
                @if ($incident->hasPriorityOverride())
                    <p class="text-sm" style="color: var(--color-state-offline)">
                        Manually set by {{ $incident->priorityOverriddenBy?->name }}:
                        “{{ $incident->priority_override_reason }}”
                    </p>
                @endif
                <ul class="text-sm space-y-1">
                    @foreach ($incident->priority_breakdown['factors'] ?? [] as $factor)
                        <li class="flex justify-between gap-3">
                            <span style="color: var(--color-ink-muted)">
                                {{ str_replace('_', ' ', ucfirst($factor['rule'])) }}
                                <span style="color: var(--color-ink-subtle)">— {{ $factor['detail'] }}</span>
                            </span>
                            <span class="font-mono tabular-nums">+{{ $factor['points'] }}</span>
                        </li>
                    @endforeach
                    <li class="flex justify-between gap-3 pt-2 mt-1 border-t font-semibold"
                        style="border-color: var(--color-border)">
                        <span>Score</span>
                        <span class="font-mono tabular-nums">{{ $incident->priority_score }}</span>
                    </li>
                </ul>

                @foreach ($incident->priority_breakdown['escalations'] ?? [] as $esc)
                    @if ($esc['applied'] ?? false)
                        <p class="text-xs" style="color: var(--color-ink-muted)">
                            Escalated: {{ $esc['note'] }}
                        </p>
                    @endif
                @endforeach
            </section>

            @if ($canAssign && ! $incident->isTerminal())
                <section class="panel p-5 space-y-3">
                    <h2 class="font-semibold">Assign responder</h2>
                    <div>
                        <label class="field-label" for="assignee">Responder</label>
                        <select id="assignee" wire:model="assignResponderId"
                                class="w-full rounded-lg border px-3 py-2 text-sm"
                                style="border-color: var(--color-border-strong)">
                            <option value="">Select…</option>
                            @foreach ($responders as $responder)
                                <option value="{{ $responder->id }}">
                                    {{ $responder->user->name }}
                                    ({{ $responder->team?->name ?? 'No team' }}, {{ $responder->status }})
                                </option>
                            @endforeach
                        </select>
                        @error('assignResponderId')
                            <p class="text-xs mt-1" style="color: var(--color-state-danger)">{{ $message }}</p>
                        @enderror
                    </div>
                    <div>
                        <label class="field-label" for="assign-notes">Notes</label>
                        <textarea id="assign-notes" wire:model="assignNotes" rows="2"
                                  class="w-full rounded-lg border px-3 py-2 text-sm"
                                  style="border-color: var(--color-border-strong)"></textarea>
                    </div>
                    <button wire:click="assign" class="btn btn-primary w-full">Assign</button>
                </section>

                <section class="panel p-5 space-y-3">
                    <h2 class="font-semibold">Override priority</h2>
                    <p class="text-xs" style="color: var(--color-ink-muted)">
                        Operators may raise priority. Lowering it requires a barangay official.
                    </p>
                    <select wire:model="overrideLevel" aria-label="New priority level"
                            class="w-full rounded-lg border px-3 py-2 text-sm"
                            style="border-color: var(--color-border-strong)">
                        <option value="">Select level…</option>
                        @foreach (['CRITICAL', 'HIGH', 'MODERATE', 'LOW'] as $level)
                            <option value="{{ $level }}">{{ $level }}</option>
                        @endforeach
                    </select>
                    <input wire:model="overrideReason" placeholder="Reason (required)"
                           aria-label="Reason for override"
                           class="w-full rounded-lg border px-3 py-2 text-sm"
                           style="border-color: var(--color-border-strong)">
                    @error('overrideLevel')
                        <p class="text-xs" style="color: var(--color-state-danger)">{{ $message }}</p>
                    @enderror
                    @error('overrideReason')
                        <p class="text-xs" style="color: var(--color-state-danger)">{{ $message }}</p>
                    @enderror
                    <button wire:click="overridePriority" class="btn btn-ghost w-full">Apply override</button>
                </section>
            @endif
        </div>
    </div>

    @script
    <script>
        const el = document.getElementById('incident-map');
        if (el && !el._map) {
            const lat = Number(el.dataset.lat), lng = Number(el.dataset.lng);
            el._map = L.map(el).setView([lat, lng], 17);
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                attribution: '&copy; OpenStreetMap contributors', maxZoom: 19,
            }).addTo(el._map);

            const colour = getComputedStyle(document.body)
                .getPropertyValue('--color-priority-' + el.dataset.priority.toLowerCase());

            L.circleMarker([lat, lng], {
                radius: 10, color: '#fff', weight: 2,
                fillColor: colour, fillOpacity: 0.95,
            }).addTo(el._map);

            // The accuracy ring is drawn honestly: a GPS fix is an estimate, and
            // a responder heading to a pin should see how large the estimate is.
            const accuracy = Number(el.dataset.accuracy);
            if (accuracy > 0) {
                L.circle([lat, lng], {
                    radius: accuracy, color: colour, weight: 1,
                    fillColor: colour, fillOpacity: 0.10,
                }).addTo(el._map);
            }
        }
    </script>
    @endscript
</div>
