package ph.bulig.app.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ph.bulig.app.Bulig
import ph.bulig.data.registration.RegistrationOutcome

/**
 * Uploads whatever the phone is holding, whenever it can.
 *
 * WorkManager rather than a service or an alarm, for one reason that matters
 * here: it survives reboots and app kills. A resident whose phone restarted
 * overnight in an evacuation centre must still have their report uploaded when
 * signal returns, without anybody opening the app.
 *
 * The work itself is one call into [ph.bulig.data.sync.SyncCoordinator], which
 * is tested against a real HTTP server. This class contributes scheduling and
 * nothing else — if a decision about *what* to upload appears here, it is in the
 * wrong place.
 *
 * @see docs/07-offline-sync.md 7.4
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val bulig = Bulig.get(applicationContext)

        Log.i(TAG, "sync run starting, base url ${Bulig.BASE_URL}")

        // Opportunistic, and never allowed to block the upload. A phone that
        // cannot register can still push packets; the server stores them with
        // hmac_valid null rather than refusing them.
        val registration = bulig.registration.ensureRegistered()
        when (registration) {
            is RegistrationOutcome.Refused -> {
                // The device is revoked. Uploading would 401 every time, and
                // retrying a revoked device forever is the battery drain the
                // failure taxonomy exists to prevent.
                Log.w(TAG, "device refused by server: ${registration.reason}; not retrying")
                return@withContext Result.failure()
            }
            is RegistrationOutcome.Deferred ->
                Log.w(TAG, "registration deferred: ${registration.reason}; uploading unsigned")
            is RegistrationOutcome.Registered -> Log.i(TAG, "device registered with the server")
            is RegistrationOutcome.AlreadyRegistered -> Log.i(TAG, "device already registered")
        }

        val outcome = try {
            bulig.syncCoordinator().syncOnce()
        } catch (e: Exception) {
            // syncOnce is documented never to throw. If it somehow does, a
            // retry is still the right answer — a crashed worker would stop
            // rescheduling and the phone would go quiet.
            Log.e(TAG, "syncOnce threw, which it is documented not to do", e)
            return@withContext Result.retry()
        }

        // Counts and outcomes only. Nothing here names a reporter, a location,
        // or the contents of a report — this log is readable by anybody with a
        // USB cable, and an emergency report is not theirs to read.
        Log.i(
            TAG,
            "sync finished: attempted=${outcome.attempted} accepted=${outcome.accepted} " +
                "duplicate=${outcome.duplicate} rejected=${outcome.rejected} " +
                "failed=${outcome.failed}" + (outcome.error?.let { " error=$it" } ?: ""),
        )

        when {
            // Nothing to send is success, not failure. An idle phone should not
            // burn its backoff budget.
            outcome.attempted == 0 -> Result.success()

            // Retry rather than failure: WorkManager's own exponential backoff
            // then applies on top of the coordinator's per-report backoff, and
            // the constraint below means it will not even run without a network.
            outcome.failed -> Result.retry()

            else -> Result.success()
        }
    }

    companion object {
        /**
         * One tag for the whole sync path, so a field test reduces to
         * `adb logcat -s BuligSync`. Counts and outcomes only ever — never a
         * report's contents, its location, or who filed it.
         */
        private const val TAG = "BuligSync"

        private const val UNIQUE_NAME = "bulig-sync"

        /**
         * Every 15 minutes when a validated network exists.
         *
         * Fifteen is WorkManager's floor for periodic work, and it is the right
         * order of magnitude anyway: a report that waits a quarter of an hour
         * for a scheduled run has usually already gone out through
         * [enqueueNow], which is triggered the moment a report is filed.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS,
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                // KEEP, not UPDATE: replacing the work on every launch would
                // reset its backoff, so an app opened repeatedly during an
                // outage would hammer a server that is already struggling.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Tries immediately — after a report is filed, or when connectivity
         * returns. Still constrained on a network, so it costs nothing offline.
         */
        fun enqueueNow(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
