package io.github.jasondorjeshort.dystopo.app.routing

import java.util.Locale

/**
 * T114: where "Route to here" starts, and how the route card names the route's two ends. The origin is the phone's
 * position: the newer of the recorder's last fix (injected or GPS) and the map's last device fix, the recorder's on a
 * tie, as `Tracking.dropWaypoint` chooses "here" (T91). A fix older than [STALE_S] still plans, with its age on the
 * card ("Your location, 14 min ago"): a mainstream navigation app plans from the device and says "Your location"; none
 * of the apps read for the finding `docs/findings/2026-10-17-route-to-flows-in-other-apps.md` documents a stale-fix
 * rule, so the number is ours. Pure Kotlin (no Android types), unit-tested on the JVM.
 */
object RouteOrigin {
    /** A position fix: where, when (wall clock, ms) and whose: `recorder` (the recorder's last fix) or `device` (the map's). */
    data class Fix(val lat: Double, val lon: Double, val timeMs: Long, val source: String)

    /**
     * One end of the route as the card names it: a placed point (a long-press, `POST /route`), or the phone's position
     * ([fix] set, with [ageS], its age when the route was planned).
     */
    data class End(val lat: Double, val lon: Double, val fix: Fix? = null, val ageS: Long? = null) {
        val isFix: Boolean get() = fix != null

        /** True for a fix older than [STALE_S] when the route was planned. */
        val stale: Boolean get() = fix != null && (ageS ?: 0) > STALE_S

        /** "Your location" (with its age when stale) or the point's coordinates. */
        val label: String get() = if (fix != null) here(ageS) else coords(lat, lon)

        fun json(): Map<String, Any?> = linkedMapOf(
            "kind" to if (fix != null) "fix" else "point", "lat" to lat, "lon" to lon, "label" to label,
            "source" to fix?.source, "fixTime" to fix?.timeMs, "ageS" to ageS, "stale" to if (fix != null) stale else null,
        )
    }

    /** The newer of the two fixes, the recorder's on a tie; null when there is neither. */
    fun choose(recorder: Fix?, device: Fix?): Fix? = when {
        recorder == null -> device
        device == null -> recorder
        recorder.timeMs >= device.timeMs -> recorder
        else -> device
    }

    /** The route end at [f], aged at [nowMs] (a fix stamped in the future counts as 0 s old). */
    fun end(f: Fix, nowMs: Long): End = End(f.lat, f.lon, f, ((nowMs - f.timeMs) / 1000).coerceAtLeast(0))

    /**
     * A fix older than this (seconds) plans with its age shown. Two minutes at a walking pace is about 170 m, inside the
     * 300 m route snap, so a younger fix starts the route on the same trails; past it the age warns that the start may be
     * off (the finding's "about 2 min").
     */
    const val STALE_S = 120L

    const val HERE = "Your location"

    /** "Your location", or with the fix's age once it is stale: "Your location, 14 min ago". */
    fun here(ageS: Long?): String = if (ageS == null || ageS <= STALE_S) HERE else "$HERE, ${ago(ageS)}"

    /** "14 min ago", "3 h ago", "2 days ago" (whole units, rounded down). */
    fun ago(s: Long): String = when {
        s < 3_600 -> "${s / 60} min ago"
        s < 48 * 3_600 -> "${s / 3_600} h ago"
        else -> "${s / 86_400} days ago"
    }

    /** A point as the long-press card shows it: "46.80240, -121.72890". */
    fun coords(lat: Double, lon: Double): String = String.format(Locale.US, "%.5f, %.5f", lat, lon)

    /** The card's lines while a route to here waits for its origin (no fix yet, or location off). */
    const val WAITING = "Waiting for your location..."
    const val LOCATION_OFF = "Location is off. Turn it on to route from where you are, or choose a start on the map."

    /** The card's line while Choose on map waits for the start's long-press. */
    const val PICK_START = "Long-press the start point"
}
