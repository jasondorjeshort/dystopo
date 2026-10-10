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
 * The device GPS through [LocationManager] (no Play services): one update a second, no distance
 * filter. Elevation is above mean sea level where Android provides it (API 34 `mslAltitudeMeters`),
 * else the raw GPS altitude, which is above the WGS84 ellipsoid (tens of meters off; the model
 * stores elevation as recorded).
 */
class GpsLocationSource(private val context: Context) : LocationSource {
    override val name = "gps"
    private var listener: LocationListener? = null
    private val manager get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked by hasPermission()
    override fun start(listener: (LocationFix) -> Unit): Boolean {
        stop()
        if (!hasPermission()) return false
        val m = manager
        if (!m.allProviders.contains(LocationManager.GPS_PROVIDER)) return false
        val l = LocationListener { loc -> listener(toFix(loc)) }
        m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper())
        this.listener = l
        return true
    }

    override fun stop() {
        listener?.let { manager.removeUpdates(it) }
        listener = null
    }

    companion object {
        fun toFix(loc: Location): LocationFix {
            val ele = when {
                Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude() -> loc.mslAltitudeMeters
                loc.hasAltitude() -> loc.altitude
                else -> null
            }
            return LocationFix(
                lat = loc.latitude, lon = loc.longitude, ele = ele, time = loc.time,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null, source = "gps",
            )
        }
    }
}
