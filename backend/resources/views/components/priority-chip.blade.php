@props(['level'])

@php
    // Priority is signalled three ways — colour, label, and icon — so it survives
    // greyscale printing, colour blindness, and a screen reader.
    $styles = [
        'CRITICAL' => ['bg' => 'var(--color-priority-critical)', 'fg' => 'var(--color-ink-inverse)', 'icon' => '▲'],
        'HIGH'     => ['bg' => 'var(--color-priority-high)',     'fg' => 'var(--color-ink-inverse)', 'icon' => '▲'],
        'MODERATE' => ['bg' => 'var(--color-priority-moderate)', 'fg' => 'var(--color-ink)',         'icon' => '●'],
        'LOW'      => ['bg' => 'var(--color-priority-low)',      'fg' => 'var(--color-ink-inverse)', 'icon' => '▼'],
    ];
    $s = $styles[$level] ?? $styles['LOW'];
@endphp

<span class="chip" style="background-color: {{ $s['bg'] }}; color: {{ $s['fg'] }}">
    <span aria-hidden="true">{{ $s['icon'] }}</span>
    <span class="sr-only">Priority</span>{{ $level }}
</span>
