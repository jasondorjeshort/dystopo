package io.github.jasondorjeshort.dystopo.app.routing

import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import io.github.jasondorjeshort.dystopo.routing.RouteFailure
import io.github.jasondorjeshort.dystopo.routing.RouteResult
import io.github.jasondorjeshort.dystopo.routing.Router
import io.github.jasondorjeshort.dystopo.routing.WayClass
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * T57: a clearer message when a tapped start or end is farther from the trail network than the snap
 * distance (the `routeSnapM` setting): it names the nearest trail or road, how far it is and in which
 * direction, and says whether a longer snap distance would reach it. Pure Kotlin, unit-tested on the JVM.
 */
object OffNetwork {
    /** What the nearest line is: its name, else its kind ("an unnamed path"). */
    fun lineName(name: String?, cls: WayClass): String = name ?: when (cls) {
        WayClass.PATH, WayClass.FOOTWAY, WayClass.CYCLEWAY, WayClass.BRIDLEWAY, WayClass.TRACK -> "an unnamed ${cls.label}"
        WayClass.STEPS -> "unnamed steps"
        else -> "an unnamed road"
    }

    private val COMPASS = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")

    /** Compass direction from (lat, lon) to (toLat, toLon), eight points. */
    fun direction(lat: Double, lon: Double, toLat: Double, toLon: Double): String {
        val dy = toLat - lat
        val dx = (toLon - lon) * cos(lat * PI / 180)
        val deg = (atan2(dx, dy) * 180 / PI + 360) % 360
        return COMPASS[((deg / 45).roundToInt()) % 8]
    }

    /**
     * The message for a point [which] ("start" or "end") with no line within [snapM]; [nearestM] is null when
     * nothing is within [Router.FAR_SEARCH_M].
     */
    fun message(which: String, snapM: Double, maxSnapM: Double, line: String?, nearestM: Double?, direction: String?, units: ContourUnits): String {
        val head = "No trail or road within ${UnitFormat.distance(snapM, units)} of the $which"
        if (line == null || nearestM == null) return "$head (none within ${UnitFormat.distance(Router.FAR_SEARCH_M, units)})."
        val where = "Nearest: $line, ${UnitFormat.distance(nearestM, units)} to the $direction."
        val hint = when {
            nearestM <= snapM -> ""
            nearestM <= maxSnapM -> " Long-press closer to it, or raise Route snap in Settings to ${UnitFormat.distance(snapUp(nearestM, maxSnapM), units)}."
            else -> " Long-press closer to it."
        }
        return "$head. $where$hint"
    }

    /** The next snap setting step (100 m) that reaches [m], capped at [max]. */
    fun snapUp(m: Double, max: Double): Double = minOf(max, kotlin.math.ceil(m / 100.0) * 100.0)

    /** [failed] with the message and details naming the nearest line; other failures unchanged. */
    fun explain(g: Router, failed: RouteResult.Failed, from: Pair<Double, Double>, to: Pair<Double, Double>, snapM: Double, maxSnapM: Double, units: ContourUnits): RouteResult.Failed {
        val which = when (failed.reason) {
            RouteFailure.START_OFF_NETWORK -> "start"
            RouteFailure.END_OFF_NETWORK -> "end"
            else -> return failed
        }
        val (lat, lon) = if (which == "start") from else to
        val snap = g.nearest(lat, lon, Router.FAR_SEARCH_M)
        val line = snap?.let { lineName(it.name, it.wayClass ?: WayClass.PATH) }
        val dir = snap?.let { direction(lat, lon, it.lat, it.lon) }
        val details = LinkedHashMap(failed.details) + linkedMapOf(
            "maxSnapM" to snapM, "snapSettingMaxM" to maxSnapM,
            "nearestName" to snap?.name, "nearestLine" to line, "nearestDirection" to dir,
            "nearestAt" to snap?.let { listOf(Math.round(it.lat * 1e6) / 1e6, Math.round(it.lon * 1e6) / 1e6) },
        )
        return RouteResult.Failed(failed.reason, message(which, snapM, maxSnapM, line, snap?.distanceM, dir, units), details)
    }
}
