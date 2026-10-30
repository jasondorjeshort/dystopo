package io.github.jasondorjeshort.dystopo.app.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import io.github.jasondorjeshort.dystopo.store.LocationFix
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where track fixes come from. The recorder doesn't care which: the phone's GPS ([GpsLocationSource]),
 * a fix posted to the debug server's `POST /location`, or a GPX replay ([InjectedLocationSource]).
 * However the map's location puck is fed (T27), the same fix reaches the recorder through here.
 */
interface LocationSource {
    val name: String

    /** Starts delivering fixes to [listener] (on any thread); false when the source can't run (no permission, no provider). */
    fun start(listener: (LocationFix) -> Unit): Boolean

    fun stop()
}

/** Fixes pushed in by code: the debug server's injected locations and GPX replays. */
class InjectedLocationSource : LocationSource {
    override val name = "injected"
    private val listeners = CopyOnWriteArrayList<(LocationFix) -> Unit>()

    @Volatile var lastPublishedAt = 0L
        private set

    fun publish(fix: LocationFix) {
        lastPublishedAt = android.os.SystemClock.elapsedRealtime()
        listeners.forEach { it(fix) }
    }

    override fun start(listener: (LocationFix) -> Unit): Boolean {
        listeners += listener
        return true
    }

    override fun stop() = listeners.clear()
}

/**
 * A device location provider through [LocationManager] (no Play services): the GPS for recording, or (T159) the
 * network or passive provider as the recording's fallback while the GPS is silent ([name] is the fix's source, which
 * the recorder flags). One update per [intervalMs] with a [distanceM] filter (the activity profile's, 1 s and none by
 * default). T159: the updates arrive on [looper], a background thread of their own, not the main thread, so a
 * recording in the background costs the main thread nothing. [start] and [stop] may be called from any thread.
 *
 * Elevation is above mean sea level where Android provides it (API 34 `mslAltitudeMeters`), else the raw GPS altitude,
 * which is above the WGS84 ellipsoid (tens of meters off; the model stores elevation as recorded). T159: the fix
 * carries the platform's speed, course (bearing) and vertical accuracy when it has them.
 */
class GpsLocationSource(
    private val context: Context,
    val provider: String = LocationManager.GPS_PROVIDER,
    override val name: String = "gps",
) : LocationSource {
    private var listener: LocationListener? = null
    private val manager get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    @Volatile var intervalMs = 1000L
    @Volatile var distanceM = 0f
    /** Times the listener was registered (the watchdog re-registers it). */
    @Volatile var registrations = 0L
        private set
    val listening: Boolean get() = listener != null

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        (provider != LocationManager.GPS_PROVIDER && context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)

    /** True when the phone has this provider at all (enabled or not). */
    fun exists(): Boolean = runCatching { manager.allProviders.contains(provider) }.getOrDefault(false)

    @Synchronized
    @SuppressLint("MissingPermission") // checked by hasPermission()
    override fun start(listener: (LocationFix) -> Unit): Boolean {
        stop()
        if (!hasPermission()) return false
        val m = manager
        if (!m.allProviders.contains(provider)) return false
        val l = locationListener { loc -> listener(toFix(loc, name)) }
        return try {
            m.requestLocationUpdates(provider, intervalMs, distanceM, l, looper)
            this.listener = l
            registrations++
            true
        } catch (e: SecurityException) {
            false
        }
    }

    @Synchronized
    override fun stop() {
        listener?.let { runCatching { manager.removeUpdates(it) } }
        listener = null
    }

    companion object {
        /** The thread location updates arrive on (T159). */
        val looper: Looper by lazy { android.os.HandlerThread("dystopo-location").apply { start() }.looper }

        fun toFix(loc: Location, source: String = "gps"): LocationFix {
            val ele = when {
                Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude() -> loc.mslAltitudeMeters
                loc.hasAltitude() -> loc.altitude
                else -> null
            }
            return LocationFix(
                lat = loc.latitude, lon = loc.longitude, ele = ele, time = loc.time,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null, source = source,
                speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                courseDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
                verticalAccuracyM = if (Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else null,
            )
        }
    }
}

/**
 * A [LocationListener] for every API level: onStatusChanged, onProviderEnabled and onProviderDisabled became default
 * methods only in API 30, so a lambda (a SAM conversion that implements onLocationChanged alone) throws
 * AbstractMethodError on Android 8 to 10 when GPS starts, stops or is switched off (found by T81's device matrix).
 */
fun locationListener(onFix: (android.location.Location) -> Unit): LocationListener = object : LocationListener {
    override fun onLocationChanged(location: android.location.Location) = onFix(location)
    @Deprecated("Deprecated in API 29; still called below it")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
