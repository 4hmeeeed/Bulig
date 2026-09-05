<header class="flex items-end justify-between gap-4 flex-wrap">
    <div>
        <h1 class="text-2xl font-bold tracking-tight">{{ $title }}</h1>
        <p class="text-sm" style="color: var(--color-ink-muted)">{{ $description }}</p>
    </div>
    @if ($searchable ?? true)
        <input wire:model.live.debounce.400ms="q" type="search" placeholder="Search"
               aria-label="Search {{ strtolower($title) }}"
               class="rounded-lg border px-3 py-2 text-sm w-64"
               style="border-color: var(--color-border-strong)">
    @endif
</header>
