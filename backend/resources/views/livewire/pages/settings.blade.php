<div class="p-6 space-y-5">
    @php $searchable = false; @endphp
    @include('livewire.pages._header')
    <p class="text-sm panel px-4 py-3" style="color: var(--color-ink-muted)">
        The priority scoring formula lives here as versioned configuration, so weights and
        bands can be tuned without a deploy. Historical decisions keep the configuration
        version they were computed under.
    </p>
    @foreach ($rows->groupBy('group') as $group => $settings)
        <section class="panel overflow-hidden">
            <div class="px-5 py-3 border-b font-semibold capitalize" style="border-color: var(--color-border)">
                {{ $group }}
            </div>
            <div class="divide-y" style="border-color: var(--color-border)">
                @foreach ($settings as $s)
                    <div class="px-5 py-3">
                        <div class="flex items-baseline justify-between gap-4">
                            <code class="text-sm font-semibold">{{ $s->key }}</code>
                            <span class="text-xs" style="color: var(--color-ink-subtle)">
                                {{ $s->updated_at?->diffForHumans() }}
                            </span>
                        </div>
                        @if ($s->description)
                            <p class="text-xs mt-0.5" style="color: var(--color-ink-muted)">{{ $s->description }}</p>
                        @endif
                        <pre class="mt-2 text-xs overflow-x-auto rounded-lg p-3"
                             style="background: var(--color-surface-raised)">{{ json_encode($s->value, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES) }}</pre>
                    </div>
                @endforeach
            </div>
        </section>
    @endforeach
</div>
