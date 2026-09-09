<?php

use App\Http\Controllers\Api\V1\AssignmentController;
use App\Http\Controllers\Api\V1\AuthController;
use App\Http\Controllers\Api\V1\DeviceController;
use App\Http\Controllers\Api\V1\IncidentController;
use App\Http\Controllers\Api\V1\MetricsController;
use App\Http\Controllers\Api\V1\SyncController;
use Illuminate\Support\Facades\Route;

Route::prefix('v1')->group(function () {
    // Public
    Route::post('auth/login', [AuthController::class, 'login'])
        ->middleware('throttle:5,1');
    Route::post('devices/register', [DeviceController::class, 'register'])
        ->middleware('throttle:3,60');

    Route::middleware('auth:sanctum')->group(function () {
        // Device-scoped: synchronisation only. A relay phone belongs to an
        // ordinary resident and must not be able to read the incident list.
        Route::middleware('device')->group(function () {
            Route::post('sync/packets', [SyncController::class, 'push'])
                ->middleware('throttle:30,1');
            Route::get('sync/pull', [SyncController::class, 'pull']);
            Route::post('devices/heartbeat', [DeviceController::class, 'heartbeat']);
        });

        // User-scoped. The `user` middleware refuses device tokens explicitly
        // rather than letting them reach a policy that expects a person.
        Route::middleware('user')->group(function () {
        Route::post('auth/logout', [AuthController::class, 'logout']);
        Route::get('auth/me', [AuthController::class, 'me']);

        Route::get('incidents', [IncidentController::class, 'index']);
        Route::get('incidents/map', [IncidentController::class, 'map']);
        Route::get('incidents/{code}', [IncidentController::class, 'show']);
        Route::get('incidents/{code}/timeline', [IncidentController::class, 'timeline']);
        Route::patch('incidents/{code}/status', [IncidentController::class, 'updateStatus']);
        Route::patch('incidents/{code}/priority', [IncidentController::class, 'updatePriority']);

        Route::get('me/assignments', [AssignmentController::class, 'mine']);
        Route::post('assignments', [AssignmentController::class, 'store']);
        Route::patch('assignments/{assignment}/accept', [AssignmentController::class, 'accept']);
        Route::patch('assignments/{assignment}/decline', [AssignmentController::class, 'decline']);
        Route::patch('assignments/{assignment}/status', [AssignmentController::class, 'updateStatus']);

            Route::middleware('role:operator,official,sysadmin')->group(function () {
                Route::get('metrics/evaluation', [MetricsController::class, 'evaluation']);
            });
        });
    });
});
