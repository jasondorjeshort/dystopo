package io.github.jasondorjeshort.dystopo.gpx

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.model.Activity
import io.github.jasondorjeshort.dystopo.model.ActivityProfile
import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.Waypoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/** A direction arrow on a line: position in pixels and the line's heading there (radians, atan2 of dy/dx, y down). */
data class LineArrow(val x: Double, val y: Double, val angle: Double)

/**
 * T260: a hole between two segments of a track that the map bridges with a dashed straight line: from the last point of
 * one segment ([from]) to the first of the next ([to]). The path walked there is unknown; [straightM] is only the
 * distance between the two ends, and distances never include it.
 */
data class GapBridge(val from: TrackPoint, val to: TrackPoint) {
    val durationMs: Long get() = (to.time ?: 0L) - (from.time ?: 0L)
    val straightM: Double get() = TrackStats.distanceM(from.lat, from.lon, to.lat, to.lon)
}

/** Platform-neutral geometry for drawing tracks on a map; renderers do the actual painting. */
object TrackDrawing {
    /**
     * The line to draw for a track, one point list per segment (T171): the smoothed line without flagged fixes
     * ([TrackSmoothing.line]), so 100 m fixes do not zigzag it; a track without accuracy (an import) is drawn
     * as recorded. The raw points stay in the store.
     */
    fun line(track: Track, options: SmoothingOptions = SmoothingOptions()): List<List<TrackPoint>> = TrackSmoothing.line(track, options)

    /**
     * T260: the gaps to bridge on the map: between each segment (empty ones skipped) and the next, when both ends have
     * times and the next starts more than [intervalMs] after the last ends (the recorder's own interval, so a gap is a
     * time the recorder should have kept a fix and did not: a lost fix, a pause, the app stopped). A track without times
     * (an import of a planned line) gets none: two segments there say nothing about a hole in time.
     */
    fun gaps(track: Track, intervalMs: Long = intervalOf(track)): List<GapBridge> = gaps(track.segments.map { it.points }, intervalMs)

    fun gaps(segments: List<List<TrackPoint>>, intervalMs: Long): List<GapBridge> {
        val out = ArrayList<GapBridge>()
        var last: TrackPoint? = null
        for (seg in segments) {
            if (seg.isEmpty()) continue
            val prev = last
            val first = seg.first()
            if (prev != null) {
                val a = prev.time
                val b = first.time
                if (a != null && b != null && b - a > intervalMs) out += GapBridge(prev, first)
            }
            last = seg.last()
        }
        return out
    }

    /** The recorder's interval for [track]: its own profile's, else its activity's default, else on foot's. */
    fun intervalOf(track: Track): Long = track.profile?.intervalMs ?: ActivityProfile.of(track.activity ?: Activity.FOOT).intervalMs

    /**
     * T260: the gaps for a statistics line, "1 gap, 10 min" or "3 gaps, 1 h 5 min" (their count and total time); null
     * with none.
     */
    fun gapText(gaps: List<GapBridge>): String? {
        if (gaps.isEmpty()) return null
        val n = gaps.size
        return "$n ${if (n == 1) "gap" else "gaps"}, ${gapDuration(gaps.sumOf { it.durationMs })}"
    }

    /** "40 s" under a minute, "10 min" under an hour, "1 h 5 min" (minutes rounded down). */
    fun gapDuration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            s % 3600 < 60 -> "${s / 3600} h"
            else -> "${s / 3600} h ${s % 3600 / 60} min"
        }
    }

    /**
     * Arrows along a pixel polyline ([xy] = x0, y0, x1, y1...), one every [spacing] pixels of line
     * length, the first [offset] pixels from the start (default half a spacing, so a short line still
     * gets one in its middle when it is at least half a spacing long).
     */
    fun arrows(xy: DoubleArray, spacing: Double, offset: Double = spacing / 2): List<LineArrow> {
        require(spacing > 0) { "spacing must be positive" }
        val out = ArrayList<LineArrow>()
        var next = offset
        var walked = 0.0
        for (i in 0 until xy.size / 2 - 1) {
            val x0 = xy[2 * i]
            val y0 = xy[2 * i + 1]
            val dx = xy[2 * i + 2] - x0
            val dy = xy[2 * i + 3] - y0
            val len = sqrt(dx * dx + dy * dy)
            if (len == 0.0) continue
            while (next <= walked + len) {
                val t = (next - walked) / len
                out += LineArrow(x0 + dx * t, y0 + dy * t, atan2(dy, dx))
                next += spacing
            }
            walked += len
        }
        return out
    }

    /**
     * T265: the shortest range of longitudes that holds every one of [lons], as (west, east) with west in -180..180 and
     * east = west + the range's width: past 180 when the short way crosses the antimeridian (the convention of
     * [BoundingBox]), so a flight from the Pacific Northwest to Japan gets the Pacific, not the rest of the world. The
     * range is the circle minus its largest empty arc between neighbouring longitudes; on a tie the range that does not
     * cross the seam wins. Null when [lons] is empty.
     */
    fun shortWayLon(lons: DoubleArray): Pair<Double, Double>? {
        if (lons.isEmpty()) return null
        val sorted = lons.map { normalizeLon(it) }.sorted()
        // The arc from the last longitude round to the first is the one that leaves the range uncrossed.
        var bestGap = sorted.first() + 360.0 - sorted.last()
        var west = sorted.first()
        var east = sorted.last()
        for (i in 0 until sorted.size - 1) {
            val gap = sorted[i + 1] - sorted[i]
            if (gap > bestGap) {
                bestGap = gap
                west = sorted[i + 1]
                east = sorted[i] + 360.0
            }
        }
        return west to east
    }

    /** -180 <= lon < 180 (180 itself becomes -180, the same meridian). */
    private fun normalizeLon(lon: Double): Double = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    /**
     * T265: (west, south, east, north) around the (lat, lon) [points], the short way round in longitude ([shortWayLon]: east
     * past 180 when that way crosses the antimeridian), with no margin; null when there are none. For a camera fit.
     */
    fun shortWayBox(points: List<Pair<Double, Double>>): DoubleArray? {
        val (w, e) = shortWayLon(DoubleArray(points.size) { points[it].second }) ?: return null
        return doubleArrayOf(w, points.minOf { it.first }, e, points.maxOf { it.first })
    }

    /**
     * The box around everything in a document (track points, route points, waypoints), grown by
     * [marginFraction] of its size on each side and to at least [minSizeM] meters across, so a
     * render fits it with some context. Null for a document without points. T265: the short way round in longitude
     * ([shortWayLon]), so a track across 180 degrees gets a box with its east edge past 180 ([BoundingBox]'s convention)
     * rather than one round the whole world.
     */
    fun fitBounds(tracks: List<Track>, routes: List<Route>, waypoints: List<Waypoint>, marginFraction: Double = 0.1, minSizeM: Double = 1000.0): BoundingBox? {
        var s = Double.POSITIVE_INFINITY
        var n = Double.NEGATIVE_INFINITY
        val lons = ArrayList<Double>()
        fun add(lat: Double, lon: Double) {
            s = minOf(s, lat); n = max(n, lat); lons += lon
        }
        tracks.forEach { t -> t.segments.forEach { seg -> seg.points.forEach { add(it.lat, it.lon) } } }
        routes.forEach { r -> r.points.forEach { add(it.lat, it.lon) } }
        waypoints.forEach { add(it.lat, it.lon) }
        val (w, e) = shortWayLon(lons.toDoubleArray()) ?: return null
        val midLat = (s + n) / 2
        val mPerDegLat = WebMercator.EARTH_CIRCUMFERENCE_M / 360.0
        val mPerDegLon = mPerDegLat * cos(midLat * kotlin.math.PI / 180.0)
        val minLat = minSizeM / mPerDegLat
        val minLon = minSizeM / max(mPerDegLon, 1.0)
        val growLon = max((e - w) * marginFraction, (minLon - (e - w)) / 2)
        val growLat = max((n - s) * marginFraction, (minLat - (n - s)) / 2)
        var west = w - growLon
        var east = e + growLon
        if (east - west >= 360.0) {
            west = -180.0; east = 180.0
        } else if (e <= 180.0) {
            // Not across the seam: the box stays inside the world, as before.
            west = west.coerceAtLeast(-180.0); east = east.coerceAtMost(180.0)
        }
        return BoundingBox(
            west, (s - growLat).coerceAtLeast(-WebMercator.MAX_LAT),
            east, (n + growLat).coerceAtMost(WebMercator.MAX_LAT),
        )
    }
}
