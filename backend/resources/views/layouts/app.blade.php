<!DOCTYPE html>
<html lang="en" class="h-full">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="csrf-token" content="{{ csrf_token() }}">
    <title>{{ $title ?? 'Bulig' }} — Barangay Command Center</title>
    @vite(['resources/css/app.css', 'resources/js/app.js'])
    @livewireStyles
</head>
<body class="h-full">
<div class="min-h-full flex">

    <aside class="w-60 shrink-0 border-r flex flex-col" style="border-color: var(--color-border); background: var(--color-surface)">
        <div class="px-5 py-5 border-b" style="border-color: var(--color-border)">
            <div class="text-lg font-bold tracking-tight">BULIG</div>
            <div class="text-xs" style="color: var(--color-ink-subtle)">Barangay Command Center</div>
        </div>

        <nav class="flex-1 px-3 py-4 space-y-6 text-sm">
            @php
                $sections = [
                    'Coordination' => [
                        ['dashboard', 'Dashboard'],
                        ['incidents', 'Incident Queue'],
                        ['map', 'Emergency Map'],
                    ],
                    'Response' => [
                        ['responders', 'Responders'],
                        ['teams', 'Rescue Teams'],
                    ],
                    'Network' => [
                        ['packets', 'Packet Monitoring'],
                        ['sync-logs', 'Synchronisation Logs'],
                    ],
                    'Administration' => [
                        ['users', 'Users'],
                        ['settings', 'Settings'],
                        ['audit-logs', 'Audit Logs'],
                    ],
                ];
            @endphp

            @foreach ($sections as $heading => $links)
                <div>
                    <div class="px-2 mb-1.5 text-[0.6875rem] font-semibold uppercase tracking-wider"
                         style="color: var(--color-ink-subtle)">{{ $heading }}</div>
                    @foreach ($links as [$route, $label])
                        @php $active = request()->routeIs($route); @endphp
                        <a href="{{ route($route) }}"
                           @if($active) aria-current="page" @endif
                           class="block rounded-lg px-2.5 py-2 font-medium"
                           style="{{ $active
                               ? 'background: var(--color-brand-soft); color: var(--color-brand-strong)'
                               : 'color: var(--color-ink-muted)' }}">
                            {{ $label }}
                        </a>
                    @endforeach
                </div>
            @endforeach
        </nav>

        <div class="px-5 py-4 border-t text-xs" style="border-color: var(--color-border); color: var(--color-ink-subtle)">
            <div class="font-medium" style="color: var(--color-ink)">{{ auth()->user()?->name }}</div>
            <div class="capitalize">{{ auth()->user()?->role }}</div>
            <form method="POST" action="{{ route('logout') }}" class="mt-2">
                @csrf
                <button class="underline">Sign out</button>
            </form>
        </div>
    </aside>

    <main class="flex-1 min-w-0">
        {{ $slot }}
    </main>
</div>
@livewireScripts
</body>
</html>
