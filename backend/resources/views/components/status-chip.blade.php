@props(['status'])

@php
    $tone = match ($status) {
        'NEW' => 'var(--color-state-danger)',
        'TRIAGED' => 'var(--color-state-offline)',
        'ASSIGNED', 'EN_ROUTE', 'ON_SITE' => 'var(--color-state-syncing)',
        'RESOLVED' => 'var(--color-state-online)',
        default => 'var(--color-ink-subtle)',
    };
@endphp

<span class="chip" style="border: 1px solid {{ $tone }}; color: {{ $tone }}">
    {{ str_replace('_', ' ', $status) }}
</span>
