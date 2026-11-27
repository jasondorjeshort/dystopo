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
 * However the map's location puck is fed (T27), the same fix reaches the recorder through here. T251: while a recording
 * takes fixes the puck is fed from the other end: the fixes the recorder keeps move it (`Tracking`'s `puck`,
 * `MapController.onRecorder`), so the map's own listener stalling can no longer leave the puck behind the track's head.
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
        // T388: the recorder's fixes go through [recorderTimes], so a phone clock set back never makes it drop good fixes.
        val l = locationListener { loc -> listener(recorderTimes.fix(toFix(loc, name), loc.elapsedRealtimeNanos / 1_000_000, android.os.SystemClock.elapsedRealtime())) }
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
        /** T388: the device fixes' times on their way to the recorder, one for every provider (the GPS and its fallbacks). */
        val recorderTimes = TrackTimes()

        /** The thread location updates arrive on (T159). */
        val looper: Looper by lazy { android.os.HandlerThread("dystopo-location").apply { start() }.looper }

        fun toFix(loc: Location, source: String = "gps"): LocationFix {
            val ele = when {
                Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude() -> loc.mslAltitudeMeters
                loc.hasAltitude() -> loc.altitude
                else -> null
            }
            return LocationFix(
                // T385: a fix whose own time is off the phone's (GpsClockCheck.OFF_MS) is kept at the phone's time for it.
                lat = loc.latitude, lon = loc.longitude, ele = ele, time = io.github.jasondorjeshort.dystopo.readout.GpsClockCheck.trackTime(loc.time,
                    io.github.jasondorjeshort.dystopo.readout.GpsClockCheck.phoneTime(loc.elapsedRealtimeNanos / 1_000_000, android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis())),
                accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null, source = source,
                speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                courseDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
                verticalAccuracyM = if (Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else null,
            )
        }
    }
}

/**
 * T388 (T385's follow-up): a device fix's time for the recorder, which drops a fix not newer than its last kept one
 * (`TrackRecorder.rejectedTime`). T385 stamps a flagged fix at the phone's time ([GpsLocationSource.toFix]); when the
 * phone's clock was the wrong one (fast) and is then set back in mid-recording, the good fixes that follow carry their own
 * times, earlier than the points already kept, and every one would be dropped until the real time caught up. So while a
 * fix's time is not after the last time handed on and the fix itself is newer on the monotonic clock (its elapsed stamp
 * later than the last one's), it is stamped at the last time plus the elapsed time between them: the track goes on at the
 * pace it was recorded at and its times never go backwards. A fix with no newer elapsed stamp (a duplicate, or an
 * out-of-order report) keeps its own time, so the recorder still drops it. Pure; `TrackTimesTest`.
 */
class TrackTimes {
    private var lastTime: Long? = null
    private var lastElapsed = 0L

    /** Fixes stamped on the monotonic continuation since the start (for a report or a test). */
    @Volatile var continued = 0L
        private set

    /** A new recording: the fixes' own times again (a continuation never carries into the next track). */
    @Synchronized
    fun reset() { lastTime = null; lastElapsed = 0L }

    /** [fix] as the recorder should take it; [stampElapsedMs] is its elapsed-realtime stamp (receipt when unusable), [nowElapsedMs] now. */
    @Synchronized
    fun fix(fix: LocationFix, stampElapsedMs: Long?, nowElapsedMs: Long): LocationFix {
        val elapsed = io.github.jasondorjeshort.dystopo.readout.ReadoutClock.fixElapsed(stampElapsedMs, nowElapsedMs) ?: nowElapsedMs
        val last = lastTime
        val time = if (last != null && fix.time <= last && elapsed > lastElapsed) { continued++; last + (elapsed - lastElapsed) } else fix.time
        if (last == null || time > last) { lastTime = time; lastElapsed = elapsed }
        return if (time == fix.time) fix else fix.copy(time = time)
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
