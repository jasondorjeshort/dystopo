package io.github.jasondorjeshort.dystopo.horizon

import io.github.jasondorjeshort.dystopo.dem.Horizon
import io.github.jasondorjeshort.dystopo.dem.HorizonProfile
import io.github.jasondorjeshort.dystopo.dem.RayMarch
import io.github.jasondorjeshort.dystopo.dem.Terrain
import io.github.jasondorjeshort.dystopo.dem.Viewshed
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** What [SightLine] found: the place in view, hidden by ground nearer than it, or out of what the terrain read can tell. */
enum class SightKind(val key: String) {
    IN_VIEW("inView"),
    HIDDEN("hidden"),
    CANNOT_TELL("cannotTell"),

    /** The place is where you stand ([HorizonPlace.HERE_M]): no line is drawn. */
    HERE("here"),
}

/**
 * T334: the answer for one place. [distanceM] and [bearingDeg] are from the observer to the place; [placeEleM] the height
 * the test stood the place at ([eleFrom] `tag` or `dem`); [angleDeg] its apparent angle and [blockDeg] the highest
 * ground's between the two (null when nothing rose there or none was read). Hidden: [ridgeM] is how far off the ground in
 * the way is and [belowM] how far below the line of sight over it the place stands. [by] says what decided: `horizon` (the
 * fix's kept profile) or `ray` (one ray to the place). [why] says why it cannot tell: `beyondRange` (past the terrain
 * read) or `noElevation` (no elevation at the place or the observer).
 */
data class Sight(
    val kind: SightKind,
    val distanceM: Double,
    val bearingDeg: Double,
    val placeEleM: Double? = null,
    val eleFrom: String? = null,
    val angleDeg: Double? = null,
    val blockDeg: Double? = null,
    val ridgeM: Double? = null,
    val belowM: Double? = null,
    val by: String? = null,
    val why: String? = null,
) {
    /** The card's line in [units] ([SightLine.text]); null for [SightKind.HERE]. */
    fun text(units: ContourUnits): String? = SightLine.text(this, units)

    fun json(units: ContourUnits): Map<String, Any?> = linkedMapOf(
        "kind" to kind.key, "text" to text(units), "by" to by, "why" to why,
        "distanceM" to r1(distanceM), "bearingDeg" to r1(bearingDeg),
        "placeEleM" to placeEleM?.let { r1(it) }, "eleFrom" to eleFrom,
        "angleDeg" to angleDeg?.let { r3(it) }, "blockDeg" to blockDeg?.let { r3(it) },
        "ridgeM" to ridgeM?.let { r1(it) }, "belowM" to belowM?.let { r1(it) },
    )

    private fun r1(v: Double) = kotlin.math.round(v * 10) / 10
    private fun r3(v: Double) = kotlin.math.round(v * 1000) / 1000
}

/**
 * T334, "Can I see it from here?" (the idea's Plan, the wave paragraph's rule): whether a place is in view from the
 * observer, by the place's own apparent angle (its height less the earth's drop, [Horizon.dropM], over its distance)
 * against the terrain's on the bearing to it.
 *
 * - With the fix's kept horizon ([HorizonProfile]): the place at or above the horizon's angle on its bearing is in view;
 *   below it with the horizon's highest ground nearer than the place, it is hidden by that ground. Any other case (the
 *   highest ground on the bearing lies beyond the place, so it says nothing about what lies between) casts one ray.
 * - Without one: one ray to the place ([RayMarch.maxTangent], in the steps [Viewshed.peaks] takes to a peak, stopping
 *   short of the place's own shoulder).
 * - "Cannot tell from here": the place beyond the terrain read (the kept horizon's reach on the bearing, the ray's
 *   coverage, or [rangeM]), or no elevation there.
 *
 * The place's height is the ground there, or [tagM] (a named peak's elevation) unless the ground differs by more than
 * 150 m, as [Viewshed.peaks] reads a tag. A place counts as seen when the line of sight passes within
 * [Viewshed.PEAK_TOLERANCE_M] (or half a terrain cell) of it.
 */
object SightLine {
    private const val DEG = PI / 180

    /** A tag more than this from the ground under it is taken for wrong, and the ground's height used ([Viewshed.peaks]). */
    const val TAG_TRUST_M = 150.0

    /**
     * The sight from ([obsLat], [obsLon]) to ([lat], [lon]). [horizon] is the fix's kept profile (its own point is the
     * observer then) or null; [terrain] the terrain it was swept over or one read around the observer for the ray (null:
     * no elevation read). [eyeHeightM] stands the eye above the ground when there is no horizon.
     */
    fun of(
        horizon: HorizonProfile?, terrain: Terrain?, obsLat: Double, obsLon: Double, lat: Double, lon: Double,
        tagM: Double? = null, rangeM: Double = Horizon.DEFAULT_RANGE_M, eyeHeightM: Double = Horizon.EYE_HEIGHT_M,
        k: Double = Horizon.REFRACTION_K,
    ): Sight {
        val oLat = horizon?.lat ?: obsLat
        val oLon = horizon?.lon ?: obsLon
        val d = TrackStats.distanceM(oLat, oLon, lat, lon)
        val b = TrackStats.bearingDeg(oLat, oLon, lat, lon)
        if (d < HorizonPlace.HERE_M) return Sight(SightKind.HERE, d, b)
        fun cannot(why: String, by: String?) = Sight(SightKind.CANNOT_TELL, d, b, by = by, why = why)
        val reach = horizon?.let { min(it.rangeM, reachedAt(it, b)) } ?: rangeM
        if (d > reach) return cannot("beyondRange", if (horizon != null) "horizon" else "ray")
        if (horizon == null && terrain != null && d > coverage(terrain, oLat, oLon, b, rangeM)) return cannot("beyondRange", "ray")

        // The place's height: the ground there, or the tag when the ground agrees within TAG_TRUST_M.
        val refMpp = terrain?.let { WebMercator.metersPerPixel(oLat, it.zoom, it.tileSize) }
        val dem = if (terrain != null && refMpp != null) {
            terrain.sample(terrain.gx(lon), terrain.gy(lat), d / refMpp).takeIf { it != Terrain.OUTSIDE && !it.isNaN() }
        } else null
        val fromDem = tagM == null || (dem != null && abs(tagM - dem) > TAG_TRUST_M)
        val ele = (if (fromDem) dem else tagM) ?: return cannot("noElevation", if (horizon != null) "horizon" else "ray")
        val eleFrom = if (fromDem) "dem" else "tag"

        val cellM = if (terrain != null && refMpp != null) cellAt(terrain, d, refMpp) else 0.0
        val tolM = max(Viewshed.PEAK_TOLERANCE_M, cellM / 2)
        val drop = Horizon.dropM(d, k)
        val stopAt = d - max(d * Viewshed.PEAK_MARGIN, 2 * cellM)

        if (horizon != null) {
            val eye = horizon.groundM + horizon.observerHeightM
            val tp = (ele - drop - eye) / d
            val angle = atan(tp) / DEG
            val h = horizon.angleAt(b)
            val hd = horizon.distanceAt(b)
            val base = Sight(SightKind.IN_VIEW, d, b, ele, eleFrom, angle, h, by = "horizon")
            if (tp + tolM / d >= tan(h * DEG)) return base
            if (hd > 0 && hd < stopAt) {
                return base.copy(kind = SightKind.HIDDEN, ridgeM = hd, belowM = eye + tan(h * DEG) * d + drop - ele)
            }
            // The highest ground on the bearing lies at or beyond the place: one ray says what lies between.
            if (terrain == null) return cannot("noElevation", "horizon")
            return ray(terrain, oLat, oLon, eye, b, d, ele, eleFrom, tolM, stopAt, drop, rangeM, k)
        }
        if (terrain == null) return cannot("noElevation", "ray")
        val ground = terrain.sample(terrain.gx(oLon), terrain.gy(oLat))
        if (ground == Terrain.OUTSIDE || ground.isNaN()) return cannot("noElevation", "ray")
        return ray(terrain, oLat, oLon, ground + eyeHeightM, b, d, ele, eleFrom, tolM, stopAt, drop, rangeM, k)
    }

    private fun ray(
        terrain: Terrain, oLat: Double, oLon: Double, eye: Double, b: Double, d: Double, ele: Double, eleFrom: String,
        tolM: Double, stopAt: Double, drop: Double, rangeM: Double, k: Double,
    ): Sight {
        val gx = terrain.gx(oLon)
        val gy = terrain.gy(oLat)
        val mpp = WebMercator.metersPerPixel(oLat, terrain.zoom, terrain.tileSize)
        val march = RayMarch(terrain, rangeM, Viewshed.PEAK_STEP_GROWTH, k)
        val east = sin(b * DEG)
        val north = cos(b * DEG)
        if (d > march.coverageM(gx, gy, east, north, 1 / mpp)) return Sight(SightKind.CANNOT_TELL, d, b, by = "ray", why = "beyondRange")
        val tp = (ele - drop - eye) / d
        val angle = atan(tp) / DEG
        val tb = if (stopAt > mpp) march.maxTangent(gx, gy, eye, east, north, 1 / mpp, mpp, rangeM = stopAt) else Double.NEGATIVE_INFINITY
        val block = if (tb == Double.NEGATIVE_INFINITY) null else atan(tb) / DEG
        val base = Sight(SightKind.IN_VIEW, d, b, ele, eleFrom, angle, block, by = "ray")
        if (tb == Double.NEGATIVE_INFINITY || tp + tolM / d >= tb) return base
        return base.copy(kind = SightKind.HIDDEN, ridgeM = march.distanceM, belowM = eye + tb * d + drop - ele)
    }

    /** How far [terrain]'s layers reach from the observer toward [bearingDeg], at most [rangeM]. */
    private fun coverage(terrain: Terrain, oLat: Double, oLon: Double, bearingDeg: Double, rangeM: Double): Double {
        val mpp = WebMercator.metersPerPixel(oLat, terrain.zoom, terrain.tileSize)
        return RayMarch(terrain, rangeM).coverageM(terrain.gx(oLon), terrain.gy(oLat), sin(bearingDeg * DEG), cos(bearingDeg * DEG), 1 / mpp)
    }

    /** How far the kept horizon's data reached toward [bearingDeg]: the lesser of the two bins about it. */
    private fun reachedAt(h: HorizonProfile, bearingDeg: Double): Double {
        val x = ((bearingDeg % 360) + 360) % 360 / h.stepDeg
        val i = floor(x).toInt() % h.size
        return min(h.reachedM[i], h.reachedM[(i + 1) % h.size])
    }

    /** The cell size (m) of the terrain layer a sample [distanceM] out reads. */
    private fun cellAt(terrain: Terrain, distanceM: Double, refMpp: Double): Double {
        val px = distanceM / refMpp
        val layer = terrain.layers.firstOrNull { px <= it.untilPx } ?: terrain.layers.last()
        return refMpp * (1L shl (terrain.zoom - layer.window.zoom).coerceAtLeast(0))
    }

    /** The line while the answer is worked out (the card draws it muted). */
    const val WORKING = "Checking the line of sight..."

    /** The line for "cannot tell". */
    const val CANNOT_TELL = "Cannot tell from here"

    /**
     * The card's line in [units]: "In view, 12.4 mi NNW"; "Hidden by a ridge 3.1 mi away, 40 m below the line of sight";
     * "Cannot tell from here"; null when the place is where you stand.
     */
    fun text(s: Sight, units: ContourUnits): String? = when (s.kind) {
        SightKind.HERE -> null
        SightKind.CANNOT_TELL -> CANNOT_TELL
        SightKind.IN_VIEW -> "In view, ${UnitFormat.distance(s.distanceM, units)} ${HorizonPlace.compass16(s.bearingDeg)}"
        SightKind.HIDDEN ->
            "Hidden by a ridge ${UnitFormat.distance(s.ridgeM ?: 0.0, units)} away, " +
                "${UnitFormat.elevation(max(1.0, s.belowM ?: 0.0), units)} below the line of sight"
    }
}
