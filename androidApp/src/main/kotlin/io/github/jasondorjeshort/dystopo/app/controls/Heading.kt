package io.github.jasondorjeshort.dystopo.app.controls

import android.location.Location
import android.os.SystemClock
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.nav.HeadingSensor
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.nav.Compass

/**
 * T100: the app's one heading source, for the facing orientation ([MapOrientation]) and navigation's readout
 * (T61, `NavSession`). Picks by [OrientationRules.heading]: a simulated heading while fixes are injected (debug:
 * `POST /camera/heading?deg=`, `/nav/heading`, `POST /location?bearing=`), the course over ground while moving
 * (the GPS's own bearing, else the direction between fixes at least [COURSE_STEP_M] apart), the compass when slow
 * or still. The compass ([HeadingSensor]: rotation vector plus the WMM declination) runs only while the map is in
 * facing and the app is in the foreground ([setCompassWanted], [setForeground]), for the battery.
 * Fixes arrive from `MapController.onFix` (every fix the location puck shows), on the main thread. T161: each also goes
 * to the live readout ([io.github.jasondorjeshort.dystopo.app.readout.LiveReadout]), which keeps its own speed and course
 * (T160's estimator, the fix's own speed over the activity's window) apart from this one's facing rules.
 */
object Heading {
    /** A course needs fixes at least this far apart (metres); GPS jitter at a standstill stays below it. */
    const val COURSE_STEP_M = 5.0

    /** A step longer than this between two fixes is a jump, not travel (metres). */
    const val JUMP_M = 2_000.0

    /** The GPS's own bearing is used above this speed (m/s), as in T61. */
    private const val GPS_BEARING_MPS = 0.7f

    val compass = Compass()
    private val sensor by lazy { HeadingSensor(DystopoApp.instance, compass) }

    @Volatile var simulated: Double? = null
        private set
    @Volatile private var courseDeg: Double? = null
    @Volatile private var courseAt = 0L
    @Volatile private var speedMps: Double? = null
    @Volatile private var from: Location? = null
    @Volatile private var wanted = false
    @Volatile private var foreground = true
    @Volatile var fixes = 0L
        private set

    /** Whether the phone has a compass (the rotation-vector sensor); facing is offered by a tap only then. */
    val compassAvailable: Boolean get() = runCatching { sensor.available }.getOrDefault(false)

    /** A simulated heading (degrees true), used while fixes are injected; null clears it. */
    fun simulate(deg: Double?) {
        simulated = deg?.let { Compass.norm(it) }
    }

    /** One fix the puck shows (main thread): declination for the compass, speed and course over ground. */
    fun onFix(loc: Location) {
        fixes++
        io.github.jasondorjeshort.dystopo.app.readout.LiveReadout.onMapFix(loc) // T161: the live readout (its own thread)
        compass.locate(loc.latitude, loc.longitude, if (loc.time > 0) loc.time else System.currentTimeMillis())
        val now = SystemClock.elapsedRealtime()
        if (loc.hasSpeed()) speedMps = loc.speed.toDouble()
        if (loc.hasBearing() && loc.hasSpeed() && loc.speed > GPS_BEARING_MPS) {
            courseDeg = loc.bearing.toDouble()
            courseAt = now
            from = loc
            return
        }
        val f = from
        if (f == null) {
            from = loc
            return
        }
        val d = TrackStats.distanceM(f.latitude, f.longitude, loc.latitude, loc.longitude)
        if (d > JUMP_M) {
            // A jump (an injected fix far away, a network fix replaced by GPS) is not a direction of travel.
            from = loc
            courseDeg = null
            speedMps = null
            return
        }
        if (d >= COURSE_STEP_M) {
            courseDeg = TrackStats.bearingDeg(f.latitude, f.longitude, loc.latitude, loc.longitude)
            courseAt = now
            // Fixes without a speed of their own (injected, replayed): the speed over that step.
            if (!loc.hasSpeed()) {
                val dt = (loc.time - f.time) / 1000.0
                speedMps = if (dt > 0) d / dt else null
            }
            from = loc
        }
    }

    /** The heading now, or null (no simulated heading, no course yet, no compass reading). */
    fun current(now: Long = SystemClock.elapsedRealtime()): HeadingReading? {
        val c = courseDeg
        val compassFresh = runCatching { sensor.fresh }.getOrDefault(false)
        return OrientationRules.heading(
            simulatedDeg = simulated,
            injected = DystopoApp.instance.map.injectedLocation,
            courseDeg = c,
            courseAgeMs = if (c == null) null else now - courseAt,
            speedMps = speedMps,
            compassDeg = compass.trueHeadingDeg,
            compassFresh = compassFresh,
        )
    }

    /** The compass runs while facing is on ([MapOrientation]) and the app is in the foreground. */
    fun setCompassWanted(on: Boolean) {
        wanted = on
        apply()
    }

    fun setForeground(on: Boolean) {
        foreground = on
        apply()
    }

    private fun apply() {
        runCatching { if (wanted && foreground) sensor.start() else sensor.stop() }
    }

    fun compassJson(): Map<String, Any?> = runCatching { sensor.json() }.getOrElse { mapOf("error" to it.message) } +
        mapOf("wanted" to wanted, "foreground" to foreground)

    fun json(): Map<String, Any?> {
        val h = current()
        val c = courseDeg
        return linkedMapOf(
            "deg" to h?.deg?.let(::r1),
            "source" to h?.source,
            "simulated" to simulated?.let(::r1),
            "courseDeg" to c?.let(::r1),
            "courseAgeMs" to if (c == null) null else SystemClock.elapsedRealtime() - courseAt,
            "speedMps" to speedMps?.let { Math.round(it * 100) / 100.0 },
            "compass" to compassJson(),
        )
    }

    private fun r1(v: Double) = Math.round(v * 10) / 10.0
}
