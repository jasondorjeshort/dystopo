package io.github.jasondorjeshort.dystopo.app.routing

import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import io.github.jasondorjeshort.dystopo.routing.RouteFailure
import io.github.jasondorjeshort.dystopo.routing.RouteResult
import io.github.jasondorjeshort.dystopo.routing.Router
import io.github.jasondorjeshort.dystopo.routing.TravelMode
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
    fun message(
        which: String, snapM: Double, maxSnapM: Double, line: String?, nearestM: Double?, direction: String?, units: ContourUnits,
        drive: Boolean = false, here: Boolean = false,
    ): String {
        // T114: an end that is the phone's position ([here]) is "your location", and pressing closer is no advice for it.
        val place = if (here) "your location" else "the $which"
        val head = (if (drive) "No road open to cars" else "No trail or road") + " within ${UnitFormat.distance(snapM, units)} of $place"
        if (line == null || nearestM == null) return "$head (none within ${UnitFormat.distance(Router.FAR_SEARCH_M, units)})."
        val where = "Nearest: $line, ${UnitFormat.distance(nearestM, units)} to the $direction."
        val raise = "raise Route snap in Settings to ${UnitFormat.distance(snapUp(nearestM, maxSnapM), units)}."
        val hint = when {
            nearestM <= snapM -> ""
            here && nearestM <= maxSnapM -> " To start on it, $raise"
            here -> ""
            nearestM <= maxSnapM -> " Long-press closer to it, or $raise"
            else -> " Long-press closer to it."
        }
        return "$head. $where$hint"
    }

    /**
     * T114: [failed] ([explain]'s) again for an end that is the phone's position: an off-network start ([start]) or end
     * (after Swap) gets [message] with `here`; anything else is returned as it is.
     */
    fun atFix(failed: RouteResult.Failed, start: Boolean, units: ContourUnits, drive: Boolean = false): RouteResult.Failed {
        if (failed.reason != if (start) RouteFailure.START_OFF_NETWORK else RouteFailure.END_OFF_NETWORK) return failed
        val d = failed.details
        val snapM = (d["maxSnapM"] as? Number)?.toDouble() ?: return failed
        val maxSnapM = (d["snapSettingMaxM"] as? Number)?.toDouble() ?: return failed
        val text = message(
            if (start) "start" else "end", snapM, maxSnapM, d["nearestLine"] as? String, (d["nearestM"] as? Number)?.toDouble(),
            d["nearestDirection"] as? String, units, drive, here = true,
        )
        return RouteResult.Failed(failed.reason, text, d)
    }

    /** The next snap setting step (100 m) that reaches [m], capped at [max]. */
    fun snapUp(m: Double, max: Double): Double = minOf(max, kotlin.math.ceil(m / 100.0) * 100.0)

    /**
     * [failed] with the message and details naming the nearest line; other failures unchanged. T73: in drive
     * mode ([mode]) the nearest road open to cars.
     */
    fun explain(
        g: Router, failed: RouteResult.Failed, from: Pair<Double, Double>, to: Pair<Double, Double>, snapM: Double, maxSnapM: Double, units: ContourUnits,
        mode: TravelMode = TravelMode.HIKE,
    ): RouteResult.Failed {
        val which = when (failed.reason) {
            RouteFailure.START_OFF_NETWORK -> "start"
            RouteFailure.END_OFF_NETWORK -> "end"
            else -> return failed
        }
        val (lat, lon) = if (which == "start") from else to
        val snap = g.nearest(lat, lon, Router.FAR_SEARCH_M, mode)
        val line = snap?.let { lineName(it.name, it.wayClass ?: WayClass.PATH) }
        val dir = snap?.let { direction(lat, lon, it.lat, it.lon) }
        val details = LinkedHashMap(failed.details) + linkedMapOf(
            "maxSnapM" to snapM, "snapSettingMaxM" to maxSnapM,
            "nearestName" to snap?.name, "nearestLine" to line, "nearestDirection" to dir,
            "nearestAt" to snap?.let { listOf(Math.round(it.lat * 1e6) / 1e6, Math.round(it.lon * 1e6) / 1e6) },
        )
        return RouteResult.Failed(failed.reason, message(which, snapM, maxSnapM, line, snap?.distanceM, dir, units, drive = mode == TravelMode.DRIVE), details)
    }
}
