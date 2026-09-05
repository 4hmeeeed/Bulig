<x-guest-layout>
    <div class="w-full max-w-sm px-6">
        <div class="mb-8 text-center">
            <div class="text-3xl font-bold tracking-tight">BULIG</div>
            <p class="text-sm mt-1" style="color: var(--color-ink-muted)">Barangay Command Center</p>
        </div>

        <form method="POST" action="{{ route('login') }}" class="panel p-6 space-y-4">
            @csrf

            <div>
                <label for="email" class="field-label">Email</label>
                <input id="email" name="email" type="email" required autofocus
                       value="{{ old('email') }}"
                       class="w-full rounded-lg border px-3 py-2"
                       style="border-color: var(--color-border-strong)">
            </div>

            <div>
                <label for="password" class="field-label">Password</label>
                <input id="password" name="password" type="password" required
                       class="w-full rounded-lg border px-3 py-2"
                       style="border-color: var(--color-border-strong)">
            </div>

            @error('email')
                <p class="text-sm" style="color: var(--color-state-danger)">{{ $message }}</p>
            @enderror

            <label class="flex items-center gap-2 text-sm">
                <input type="checkbox" name="remember"> Remember me
            </label>

            <button type="submit" class="btn btn-primary w-full">Sign in</button>
        </form>

        <p class="text-xs text-center mt-6" style="color: var(--color-ink-subtle)">
            Bulig is a capstone prototype. It does not replace official emergency services.
        </p>
    </div>
</x-guest-layout>
