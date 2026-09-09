<?php

use App\Livewire\Dashboard;
use App\Livewire\IncidentDetail;
use App\Livewire\IncidentQueue;
use App\Livewire\PacketMonitor;
use App\Livewire\SimpleTable;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;
use Illuminate\Support\Facades\Route;

Route::get('/', fn () => redirect()->route('dashboard'));

Route::get('login', fn () => view('auth.login'))->name('login')->middleware('guest');

Route::post('login', function (Request $request) {
    $credentials = $request->validate([
        'email' => ['required', 'email'],
        'password' => ['required'],
    ]);

    if (! Auth::attempt($credentials, $request->boolean('remember'))) {
        return back()->withErrors(['email' => 'These credentials do not match our records.'])
            ->onlyInput('email');
    }

    if (! Auth::user()->isStaff()) {
        Auth::logout();

        return back()->withErrors([
            'email' => 'The command center is for barangay staff. Residents and responders use the mobile app.',
        ])->onlyInput('email');
    }

    $request->session()->regenerate();
    Auth::user()->forceFill(['last_login_at' => now()])->save();

    return redirect()->intended(route('dashboard'));
})->middleware('guest');

Route::post('logout', function (Request $request) {
    Auth::logout();
    $request->session()->invalidate();
    $request->session()->regenerateToken();

    return redirect()->route('login');
})->name('logout');

// The command center is staff-only. Residents and responders never reach it.
Route::middleware(['auth', 'role:operator,official,sysadmin'])->group(function () {
    Route::get('dashboard', Dashboard::class)->name('dashboard');
    Route::get('incidents', IncidentQueue::class)->name('incidents');
    Route::get('incidents/{code}', IncidentDetail::class)->name('incidents.show');
    Route::get('packets', PacketMonitor::class)->name('packets');

    Route::get('map', SimpleTable::class)->defaults('page_key', 'map')->name('map');
    Route::get('responders', SimpleTable::class)->defaults('page_key', 'responders')->name('responders');
    Route::get('teams', SimpleTable::class)->defaults('page_key', 'teams')->name('teams');
    Route::get('sync-logs', SimpleTable::class)->defaults('page_key', 'sync-logs')->name('sync-logs');

    Route::middleware('role:official,sysadmin')->group(function () {
        Route::get('users', SimpleTable::class)->defaults('page_key', 'users')->name('users');
        Route::get('settings', SimpleTable::class)->defaults('page_key', 'settings')->name('settings');
        Route::get('audit-logs', SimpleTable::class)->defaults('page_key', 'audit-logs')->name('audit-logs');
    });
});
