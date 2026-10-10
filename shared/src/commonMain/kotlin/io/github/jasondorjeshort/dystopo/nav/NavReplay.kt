package io.github.jasondorjeshort.dystopo.nav

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Off-route logic without a device (T65, `devtool nav replay`): runs a list of fixes through a
 * [Navigator] on a [NavPath] exactly as the phone does with `POST /nav/start` plus `POST /location/replay`
 * (the fixes' own times decide the off-route timer, so the result does not depend on a replay rate) and
 * reports the events, how far off the line the walk went, the progress and the pace ratio
 * ([Navigator.etaVsPlan]). The phone's alert snooze and stale-fix rules ([AlertSnooze], [FixFreshness])
 * only decide what sounds and which device fixes count; replayed fixes are always used.
 */
object NavReplay {
    /** One fix: position, its time (epoch ms) and the horizontal accuracy when known. */
    data class Fix(val lat: Double, val lon: Double, val timeMs: Long, val accuracyM: Double? = null)

    /** Runs [fixes] in order; [samples] progress samples (evenly by fix index) go in the report. */
    fun run(path: NavPath, fixes: List<Fix>, options: NavOptions = NavOptions(), samples: Int = 20): Map<String, Any?> {
        val nav = Navigator(path, options)
        var maxOff = 0.0
        var maxOffAlong = 0.0
        var offFixes = 0
        var firstEta: Double? = null
        val every = max(1, fixes.size / max(1, samples))
        val progress = ArrayList<Map<String, Any?>>()
        for ((i, f) in fixes.withIndex()) {
            val s = nav.onFix(f.lat, f.lon, f.timeMs, f.accuracyM)
            if (i == 0) firstEta = nav.etaVsPlan()
            if (s.projection.distanceM > maxOff) {
                maxOff = s.projection.distanceM
                maxOffAlong = s.progress
            }
            if (s.offRoute) offFixes++
            if (i % every == 0 || i == fixes.size - 1) {
                progress += linkedMapOf(
                    "fix" to i, "timeMs" to f.timeMs, "progressM" to r1(s.progress), "distanceM" to r1(s.projection.distanceM),
                    "offRoute" to s.offRoute, "endM" to r1(s.end.distanceM), "endS" to s.end.timeS.roundToLong(), "etaVsPlan" to nav.etaVsPlan()?.let(::r3),
                )
            }
        }
        val s = nav.state
        val events = nav.events
        return linkedMapOf(
            "path" to linkedMapOf(
                "name" to path.name, "points" to path.points.size, "totalM" to r1(path.totalM),
                "ascentM" to r1(path.totalAscentM), "descentM" to r1(path.totalDescentM), "hasElevation" to path.hasElevation,
                "plannedTimeS" to nav.plannedTimeS.roundToLong(), "waypoints" to path.waypoints.map { linkedMapOf("name" to it.name, "alongM" to r1(it.alongM)) },
            ),
            "options" to linkedMapOf(
                "offRouteM" to options.offRouteM, "offRouteSeconds" to options.offRouteSeconds, "backOnRouteM" to options.backOnRouteM,
                "arriveM" to options.arriveM, "maxAccuracyM" to options.maxAccuracyM, "mode" to options.mode.label,
            ),
            "fixes" to fixes.size,
            "offRouteFixes" to offFixes,
            "maxDistanceM" to r1(maxOff),
            "maxDistanceAlongM" to r1(maxOffAlong),
            "arrived" to (s?.arrived ?: false),
            "finalProgressM" to s?.progress?.let(::r1),
            "etaVsPlanAtStart" to firstEta?.let(::r3),
            "etaVsPlanAtEnd" to nav.etaVsPlan()?.let(::r3),
            "counts" to events.groupingBy { it.type.substringBefore(':') }.eachCount(),
            "events" to events.map { it.json() },
            "progress" to progress,
        )
    }

    private fun r3(v: Double) = (v * 1000).roundToLong() / 1000.0
}
