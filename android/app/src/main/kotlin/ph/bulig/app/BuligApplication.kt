package ph.bulig.app

import android.app.Application

/**
 * Application entry point.
 *
 * Deliberately empty for slice 1. Dependency wiring, the Room database and the
 * BLE foreground service arrive in later slices — once this one is confirmed to
 * build on a real machine.
 */
class BuligApplication : Application()
