package ph.bulig.app

import android.app.Application
import ph.bulig.app.sync.SyncWorker

/**
 * Application entry point.
 *
 * Does two things and no more: schedules the sync worker, and leaves everything
 * else lazy.
 *
 * Nothing here opens the database or touches the Keystore. A cold start may be a
 * resident opening the app to file an emergency, and every millisecond spent
 * decrypting storage before the first frame is a millisecond somebody spends
 * looking at a blank screen in a flood. [Bulig] builds each dependency the first
 * time it is actually needed.
 */
class BuligApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Survives reboots and app kills, which is the point: a resident whose
        // phone restarted overnight in an evacuation centre must still have
        // their report uploaded when signal returns, without opening the app.
        SyncWorker.schedule(this)
    }
}
