package ph.bulig.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import ph.bulig.data.location.LocationFix

/**
 * The device's position, when it can get one.
 *
 * The fused provider rather than raw `LocationManager`: it combines GPS, network
 * and sensors, which matters indoors and under cover — exactly the conditions a
 * resident on a roof during a flood is in, and exactly where a GPS-only fix
 * never arrives.
 *
 * Every decision about what to *do* with a fix — whether it is good enough,
 * whether it has gone stale, whether a new one should replace it — belongs to
 * `LocationPolicy` in `:data`, which has 18 tests. This class only fetches.
 */
class FusedLocationSource(private val context: Context) {

    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Asks for a fresh fix, and returns null rather than waiting forever.
     *
     * `PRIORITY_HIGH_ACCURACY` because this is an emergency, and the battery
     * cost of one fix is irrelevant next to a rescue team going to the wrong
     * house. The cancellation token means the request stops when the caller's
     * scope does — a resident who backs out of the flow must not leave the GPS
     * radio running.
     *
     * Null is a normal outcome, not an error: the flow is built to send a report
     * with no location at all.
     */
    @SuppressLint("MissingPermission")
    suspend fun currentFix(): LocationFix? {
        if (!hasPermission()) return null

        return try {
            suspendCancellableCoroutine { continuation ->
                val token = com.google.android.gms.tasks.CancellationTokenSource()

                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
                    .addOnSuccessListener { location ->
                        continuation.resume(
                            location?.let {
                                LocationFix(
                                    latitude = it.latitude,
                                    longitude = it.longitude,
                                    // A provider that reports no accuracy is
                                    // treated as maximally vague rather than
                                    // perfect — the safe direction to guess in.
                                    accuracyM = if (it.hasAccuracy()) it.accuracy.toDouble() else 9_999.0,
                                    capturedAtMs = it.time,
                                    provider = it.provider ?: "fused",
                                )
                            }
                        )
                    }
                    .addOnFailureListener { continuation.resume(null) }

                continuation.invokeOnCancellation { token.cancel() }
            }
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            // Play Services missing or out of date. Common on cheap and
            // grey-market handsets, and not a reason to fail the report flow.
            null
        }
    }

    /**
     * The last fix the system already had, which costs nothing to read.
     *
     * Used to show something immediately while [currentFix] is still working.
     * `LocationPolicy.isStale` decides whether it is still worth trusting.
     */
    @SuppressLint("MissingPermission")
    suspend fun lastKnownFix(): LocationFix? {
        if (!hasPermission()) return null

        return try {
            suspendCancellableCoroutine { continuation ->
                client.lastLocation
                    .addOnSuccessListener { location ->
                        continuation.resume(
                            location?.let {
                                LocationFix(
                                    latitude = it.latitude,
                                    longitude = it.longitude,
                                    accuracyM = if (it.hasAccuracy()) it.accuracy.toDouble() else 9_999.0,
                                    capturedAtMs = it.time,
                                    provider = it.provider ?: "fused-cached",
                                )
                            }
                        )
                    }
                    .addOnFailureListener { continuation.resume(null) }
            }
        } catch (e: Exception) {
            null
        }
    }
}
