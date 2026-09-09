<div class="p-6 space-y-5">
    @php $searchable = false; @endphp
    @include('livewire.pages._header')

    <div class="flex flex-wrap gap-4 text-sm panel px-4 py-3" role="list" aria-label="Priority legend">
        @foreach (['CRITICAL', 'HIGH', 'MODERATE', 'LOW'] as $level)
            <div role="listitem"><x-priority-chip :level="$level" /></div>
        @endforeach
        <span class="ml-auto text-xs" style="color: var(--color-ink-muted)">
            Rings show reported GPS accuracy. A pin is an estimate, not a confirmed address.
        </span>
    </div>

    <section class="panel overflow-hidden">
        <div wire:ignore id="full-map" class="h-[calc(100vh-16rem)] w-full"
             data-markers="{{ json_encode($rows->filter(fn($e) => $e->location)->map(fn($e) => [
                 'code' => $e->emergency_code,
                 'lat' => (float) $e->location->latitude,
                 'lng' => (float) $e->location->longitude,
                 'accuracy' => (float) ($e->location->accuracy_m ?? 0),
                 'priority' => $e->priority_level,
                 'type' => $e->type->label_en,
                 'status' => $e->status,
                 'hops' => $e->first_hop_count,
                 'url' => route('incidents.show', $e->emergency_code),
             ])->values()) }}"
             data-center="{{ json_encode(\App\Models\Setting::get('map_default_center', ['lat' => 11.2444, 'lng' => 125.0048])) }}"
             data-zoom="{{ \App\Models\Setting::get('map_default_zoom', 15) }}"></div>
    </section>

    @script
    <script>
        const el = document.getElementById('full-map');
        if (el && !el._map) {
            const center = JSON.parse(el.dataset.center);
            el._map = L.map(el).setView([center.lat, center.lng], Number(el.dataset.zoom));
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                attribution: '&copy; OpenStreetMap contributors', maxZoom: 19,
            }).addTo(el._map);

            const style = getComputedStyle(document.body);
            const colour = (p) => style.getPropertyValue('--color-priority-' + p.toLowerCase());
            const markers = JSON.parse(el.dataset.markers);

            markers.forEach((m) => {
                const c = colour(m.priority);
                if (m.accuracy > 0) {
                    L.circle([m.lat, m.lng], {
                        radius: m.accuracy, color: c, weight: 1,
                        fillColor: c, fillOpacity: 0.08,
                    }).addTo(el._map);
                }
                L.circleMarker([m.lat, m.lng], {
                    radius: m.priority === 'CRITICAL' ? 12 : 9,
                    color: '#fff', weight: 2, fillColor: c, fillOpacity: 0.95,
                }).bindPopup(
                    `<strong>${m.code}</strong><br>${m.type}<br>` +
                    `${m.priority} &middot; ${m.status.replace('_', ' ')}<br>` +
                    `<small>${m.hops} hop(s) &middot; accuracy ${Math.round(m.accuracy)} m</small><br>` +
                    `<a href="${m.url}">Open incident</a>`
                ).addTo(el._map);
            });

            if (markers.length) {
                el._map.fitBounds(markers.map((m) => [m.lat, m.lng]), { padding: [50, 50], maxZoom: 17 });
            }
        }
    </script>
    @endscript
</div>
