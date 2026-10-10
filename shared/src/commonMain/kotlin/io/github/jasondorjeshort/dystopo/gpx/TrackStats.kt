package io.github.jasondorjeshort.dystopo.gpx

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Tunables for [TrackStats]. The defaults suit hiking with a phone or handheld GPS. */
data class StatsOptions(
    /**
     * Elevation hysteresis in meters: an up or down run counts toward ascent/descent only once the
     * elevation has moved this far from the last turning point (see [TrackStats.ascentDescent]).
     */
    val elevationThresholdM: Double = 3.0,
    /** An interval between two timed points counts as moving when its ground speed is at least this (0.3 m/s = 1.08 km/h). */
    val movingSpeedMps: Double = 0.3,
)

/** One sample of an elevation profile: [distanceM] along the track, interpolated position and elevation. */
data class ProfileSample(val distanceM: Double, val eleM: Double?, val lat: Double, val lon: Double)

/**
 * Derived numbers for a track or route. Distances in meters, times in seconds (epoch millis for
 * [startTime]/[endTime]), speeds in m/s, pace in seconds per kilometer. Fields that need data the
 * track doesn't have (elevation, time, heart rate) are null.
 */
data class TrackStats(
    val segments: Int,
    val points: Int,
    /** Ground distance, summed within segments (never across the gap between two segments). */
    val distanceM: Double,
    val ascentM: Double?,
    val descentM: Double?,
    val minEleM: Double?,
    val maxEleM: Double?,
    val startTime: Long?,
    val endTime: Long?,
    /** From the first to the last timestamp, pauses and segment gaps included. */
    val durationS: Double?,
    /** Sum of the intervals whose speed reaches [StatsOptions.movingSpeedMps]. */
    val movingTimeS: Double?,
    /** Moving distance over moving time. */
    val movingSpeedMps: Double?,
    /** Moving time per kilometer of moving distance. */
    val movingPaceSPerKm: Double?,
    /** Elapsed time ([durationS]) per kilometer of [distanceM]. */
    val overallPaceSPerKm: Double?,
    val avgHeartRate: Double?,
    val maxHeartRate: Double?,
    val bounds: BoundingBox?,
) {
    companion object {
        /** Mean Earth radius (IUGG), meters. */
        const val EARTH_RADIUS_M = 6_371_008.8

        fun of(track: Track, options: StatsOptions = StatsOptions()): TrackStats =
            of(track.segments.map { it.points }, options)

        /** A route is one segment of its points; its stats are distance and elevation only (route times are rare). */
        fun of(route: Route, options: StatsOptions = StatsOptions()): TrackStats =
            of(listOf(route.points.map { TrackPoint(it.lat, it.lon, it.ele, it.time) }), options)

        fun of(segments: List<List<TrackPoint>>, options: StatsOptions = StatsOptions()): TrackStats {
            var distance = 0.0
            var movingDistance = 0.0
            var movingMs = 0L
            var anyInterval = false
            var ascent = 0.0
            var descent = 0.0
            var anyEle = false
            var minEle = Double.POSITIVE_INFINITY
            var maxEle = Double.NEGATIVE_INFINITY
            var start: Long? = null
            var end: Long? = null
            var hrSum = 0.0
            var hrCount = 0
            var hrMax = Double.NEGATIVE_INFINITY
            var west = Double.POSITIVE_INFINITY
            var east = Double.NEGATIVE_INFINITY
            var south = Double.POSITIVE_INFINITY
            var north = Double.NEGATIVE_INFINITY

            for (seg in segments) {
                val eles = ArrayList<Double>(seg.size)
                for ((i, p) in seg.withIndex()) {
                    west = min(west, p.lon); east = maxOf(east, p.lon)
                    south = min(south, p.lat); north = maxOf(north, p.lat)
                    p.ele?.let {
                        eles += it
                        anyEle = true
                        minEle = min(minEle, it)
                        maxEle = maxOf(maxEle, it)
                    }
                    p.time?.let {
                        if (start == null || it < start!!) start = it
                        if (end == null || it > end!!) end = it
                    }
                    GpxExtensions.heartRate(p.extensions)?.let {
                        hrSum += it; hrCount++; hrMax = maxOf(hrMax, it)
                    }
                    if (i == 0) continue
                    val q = seg[i - 1]
                    val d = distanceM(q.lat, q.lon, p.lat, p.lon)
                    distance += d
                    val t0 = q.time
                    val t1 = p.time
                    if (t0 != null && t1 != null && t1 > t0) {
                        anyInterval = true
                        val dt = t1 - t0
                        if (d / (dt / 1000.0) >= options.movingSpeedMps) {
                            movingMs += dt
                            movingDistance += d
                        }
                    }
                }
                val (a, de) = ascentDescent(eles, options.elevationThresholdM)
                ascent += a
                descent += de
            }

            val points = segments.sumOf { it.size }
            val duration = if (start != null && end != null) (end!! - start!!) / 1000.0 else null
            val moving = if (anyInterval) movingMs / 1000.0 else null
            return TrackStats(
                segments = segments.size,
                points = points,
                distanceM = distance,
                ascentM = if (anyEle) ascent else null,
                descentM = if (anyEle) descent else null,
                minEleM = if (anyEle) minEle else null,
                maxEleM = if (anyEle) maxEle else null,
                startTime = start,
                endTime = end,
                durationS = duration,
                movingTimeS = moving,
                movingSpeedMps = moving?.takeIf { it > 0 }?.let { movingDistance / it },
                movingPaceSPerKm = moving?.takeIf { movingDistance >= 1.0 }?.let { it / (movingDistance / 1000.0) },
                overallPaceSPerKm = duration?.takeIf { distance >= 1.0 }?.let { it / (distance / 1000.0) },
                avgHeartRate = if (hrCount > 0) hrSum / hrCount else null,
                maxHeartRate = if (hrCount > 0) hrMax else null,
                bounds = if (points > 0 && west < east && south < north) BoundingBox(west, south, east, north) else null,
            )
        }

        /** Great-circle (haversine) distance in meters on a sphere of [EARTH_RADIUS_M]. */
        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = lat1 * DEG
            val p2 = lat2 * DEG
            val dp = p2 - p1
            val dl = (lon2 - lon1) * DEG
            val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * EARTH_RADIUS_M * asin(sqrt(min(1.0, h)))
        }

        /** Initial bearing from point 1 to point 2, degrees clockwise from north, 0..360. */
        fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = lat1 * DEG
            val p2 = lat2 * DEG
            val dl = (lon2 - lon1) * DEG
            val b = atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)) / DEG
            return (b + 360.0) % 360.0
        }

        /**
         * Total ascent and descent of an elevation series with turning-point hysteresis: the series
         * is split into alternating up and down runs between turning points, and a run is counted
         * only once the elevation has moved at least [threshold] meters past the last turning point
         * against the current direction. So a reversal smaller than [threshold] (GPS or barometer
         * noise, a boulder) is ignored, while a long steady climb in small steps counts in full,
         * and the final run counts to its extreme. Ascent minus descent is within [threshold] of
         * last minus first (exactly equal when the series ends at the extreme of its last run).
         */
        fun ascentDescent(eles: List<Double>, threshold: Double): Pair<Double, Double> {
            if (eles.size < 2) return 0.0 to 0.0
            var ascent = 0.0
            var descent = 0.0
            var turn = eles[0] // elevation at the last turning point
            var extreme = eles[0] // furthest point of the current run
            var dir = 0 // +1 climbing, -1 descending, 0 not yet decided
            for (i in 1 until eles.size) {
                val e = eles[i]
                when (dir) {
                    0 -> if (e - turn >= threshold) { dir = 1; extreme = e } else if (turn - e >= threshold) { dir = -1; extreme = e }
                    1 -> if (e > extreme) extreme = e else if (extreme - e >= threshold) {
                        ascent += extreme - turn
                        turn = extreme; extreme = e; dir = -1
                    }
                    else -> if (e < extreme) extreme = e else if (e - extreme >= threshold) {
                        descent += turn - extreme
                        turn = extreme; extreme = e; dir = 1
                    }
                }
            }
            if (dir == 1) ascent += extreme - turn else if (dir == -1) descent += turn - extreme
            return ascent to descent
        }

        /**
         * [n] samples evenly spaced by distance from the first point (0 m) to the last
         * (the total distance), across segments (the gap between segments adds no distance).
         * Elevations are interpolated linearly between points that have one; points without
         * elevation are passed over. Empty for no points or n < 2.
         */
        fun profile(segments: List<List<TrackPoint>>, n: Int): List<ProfileSample> {
            val pts = segments.flatten()
            if (pts.isEmpty() || n < 2) return emptyList()
            val cum = DoubleArray(pts.size)
            var k = 0
            for (seg in segments) {
                for ((i, p) in seg.withIndex()) {
                    cum[k] = when {
                        k == 0 -> 0.0
                        i == 0 -> cum[k - 1] // segment gap: no distance
                        else -> cum[k - 1] + distanceM(seg[i - 1].lat, seg[i - 1].lon, p.lat, p.lon)
                    }
                    k++
                }
            }
            val total = cum.last()
            val eleIdx = pts.indices.filter { pts[it].ele != null }
            val out = ArrayList<ProfileSample>(n)
            var j = 0 // pts index with cum[j] <= d
            var e = 0 // position in eleIdx with cum[eleIdx[e]] <= d
            for (s in 0 until n) {
                val d = total * s / (n - 1)
                while (j < pts.size - 2 && cum[j + 1] <= d) j++
                val a = pts[j]
                val b = pts[min(j + 1, pts.size - 1)]
                val span = cum[min(j + 1, pts.size - 1)] - cum[j]
                val t = if (span > 0) ((d - cum[j]) / span).coerceIn(0.0, 1.0) else 0.0
                val ele = if (eleIdx.isEmpty()) null else {
                    while (e < eleIdx.size - 1 && cum[eleIdx[e + 1]] <= d) e++
                    val i0 = eleIdx[e]
                    val i1 = eleIdx[min(e + 1, eleIdx.size - 1)]
                    val e0 = pts[i0].ele!!
                    val e1 = pts[i1].ele!!
                    val es = cum[i1] - cum[i0]
                    if (es > 0 && d > cum[i0]) e0 + (e1 - e0) * ((d - cum[i0]) / es).coerceIn(0.0, 1.0) else e0
                }
                out += ProfileSample(d, ele, a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
            }
            return out
        }

        fun profile(track: Track, n: Int): List<ProfileSample> = profile(track.segments.map { it.points }, n)

        private const val DEG = kotlin.math.PI / 180.0
    }
}
