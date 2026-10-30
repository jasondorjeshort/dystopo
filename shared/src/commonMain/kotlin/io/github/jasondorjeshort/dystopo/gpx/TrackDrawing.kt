package io.github.jasondorjeshort.dystopo.gpx

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.WebMercator
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

/** Platform-neutral geometry for drawing tracks on a map; renderers do the actual painting. */
object TrackDrawing {
    /**
     * The line to draw for a track, one point list per segment (T171): the smoothed line without flagged fixes
     * ([TrackSmoothing.line]), so 100 m fixes do not zigzag it; a track without accuracy (an import) is drawn
     * as recorded. The raw points stay in the store.
     */
    fun line(track: Track, options: SmoothingOptions = SmoothingOptions()): List<List<TrackPoint>> = TrackSmoothing.line(track, options)

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
     * The box around everything in a document (track points, route points, waypoints), grown by
     * [marginFraction] of its size on each side and to at least [minSizeM] meters across, so a
     * render fits it with some context. Null for a document without points.
     */
    fun fitBounds(tracks: List<Track>, routes: List<Route>, waypoints: List<Waypoint>, marginFraction: Double = 0.1, minSizeM: Double = 1000.0): BoundingBox? {
        var w = Double.POSITIVE_INFINITY
        var e = Double.NEGATIVE_INFINITY
        var s = Double.POSITIVE_INFINITY
        var n = Double.NEGATIVE_INFINITY
        fun add(lat: Double, lon: Double) {
            w = minOf(w, lon); e = max(e, lon); s = minOf(s, lat); n = max(n, lat)
        }
        tracks.forEach { t -> t.segments.forEach { seg -> seg.points.forEach { add(it.lat, it.lon) } } }
        routes.forEach { r -> r.points.forEach { add(it.lat, it.lon) } }
        waypoints.forEach { add(it.lat, it.lon) }
        if (w > e) return null
        val midLat = (s + n) / 2
        val mPerDegLat = WebMercator.EARTH_CIRCUMFERENCE_M / 360.0
        val mPerDegLon = mPerDegLat * cos(midLat * kotlin.math.PI / 180.0)
        val minLat = minSizeM / mPerDegLat
        val minLon = minSizeM / max(mPerDegLon, 1.0)
        val growLon = max((e - w) * marginFraction, (minLon - (e - w)) / 2)
        val growLat = max((n - s) * marginFraction, (minLat - (n - s)) / 2)
        return BoundingBox(
            (w - growLon).coerceAtLeast(-180.0), (s - growLat).coerceAtLeast(-WebMercator.MAX_LAT),
            (e + growLon).coerceAtMost(180.0), (n + growLat).coerceAtMost(WebMercator.MAX_LAT),
        )
    }
}
