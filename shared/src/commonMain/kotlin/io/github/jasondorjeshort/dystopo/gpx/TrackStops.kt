package io.github.jasondorjeshort.dystopo.gpx

import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint

/**
 * Tunables for [TrackStops]. A stop is a stay of at least [minStopS] within [radiusM] of where it began: an
 * average speed under [radiusM] / [minStopS] (0.25 m/s by default) for over a minute. The research of 2026-10-30
 * (T171's report): a radius-and-time test on smoothed positions rather than a speed threshold on differenced
 * fixes, which reads a standing phone's jitter as 0.5 to 2 m/s; foot thresholds of 0.28 to 0.5 m/s elsewhere.
 */
data class StopOptions(
    /** How far the smoothed position may wander during a stop, metres, the climb included (as gpxpy's 3D moving test). */
    val radiusM: Double = 15.0,
    /** How long a stay must last to be a stop, seconds: a 3-minute rest is cut, a 30 s pause is not. */
    val minStopS: Double = 60.0,
    /**
     * A stay is a stop only when its median speed (the receiver's where it gave one, else the smoother's) is at most
     * this, m/s: [StatsOptions.movingSpeedMps]'s 0.3. A segment without speeds (not smoothed) skips the check.
     */
    val stillSpeedMps: Double = 0.3,
    /** The smoothing the positions go through first ([TrackSmoothing]); null for the raw points. */
    val smoothing: SmoothingOptions? = SmoothingOptions(),
)

/**
 * A stop in segment [segment] of a track, from point [startIndex] (where the stay began) to [endIndex] (the last
 * point still within the radius), both indices into the segment's raw points, which are also the indices of
 * [TrackSmoothing.smooth]'s points. Times are epoch millis.
 */
data class TrackStop(val segment: Int, val startIndex: Int, val endIndex: Int, val startTime: Long, val endTime: Long) {
    val durationS: Double get() = (endTime - startTime) / 1000.0
}

/**
 * A moving stretch between stops (or a segment's ends): points [startIndex] to [endIndex] of segment [segment]
 * (raw indices, inclusive; a stop's end point starts the next stretch), [distanceM] along the smoothed line
 * without flagged fixes. Never crosses a segment boundary, so never a gap either.
 */
data class TrackStretch(
    val segment: Int,
    val startIndex: Int,
    val endIndex: Int,
    val startTime: Long,
    val endTime: Long,
    val distanceM: Double,
) {
    val durationS: Double get() = (endTime - startTime) / 1000.0
}

/** What [TrackStops.cut] found: the stops and the moving stretches between them, in track order. */
data class StopCut(val stops: List<TrackStop>, val stretches: List<TrackStretch>) {
    val stoppedS: Double get() = stops.sumOf { it.durationS }
}

/**
 * The stop cut (T171, for T164's pace fit and the route-times idea): stays longer than a minute taken out of a
 * recorded track, leaving the moving stretches whose distance, climb and time a pace can be fitted to.
 *
 * The test runs per segment on the smoothed points that count ([TrackSmoothing.counts]; all of them in a segment
 * with fewer than two that do): from each point, the following points within [StopOptions.radiusM] of it form a
 * stay; one lasting [StopOptions.minStopS] or more, with a median speed of at most [StopOptions.stillSpeedMps], is
 * a stop, extended while the next point is within the radius of the stay's centre, and the search resumes after it. A segment whose
 * points do not all have times has no stops and no stretches. Usage: `TrackStops.cut(track).stretches`, then
 * the points of a stretch from `track.segments[s.segment].points` (raw) or `TrackSmoothing.smooth(track)[s.segment]`
 * (smoothed) between its indices.
 */
object TrackStops {
    fun cut(track: Track, options: StopOptions = StopOptions()): StopCut = cut(track.segments.map { it.points }, options)

    fun cut(segments: List<List<TrackPoint>>, options: StopOptions = StopOptions()): StopCut {
        val stops = ArrayList<TrackStop>()
        val stretches = ArrayList<TrackStretch>()
        for ((si, raw) in segments.withIndex()) {
            if (raw.isEmpty() || raw.any { it.time == null }) continue
            val smoothed = options.smoothing?.let { TrackSmoothing.smoothWithSpeed(raw, it) }
            val seg = smoothed?.points ?: raw
            // The receiver's own (Doppler) speed where it gave one, else the smoother's.
            val speed = DoubleArray(seg.size) { raw[it].speedMps ?: smoothed?.speedMps?.get(it) ?: Double.NaN }
            val counted = seg.indices.filter { TrackSmoothing.counts(seg[it]) }
            val idx = if (counted.size >= 2) counted else seg.indices.toList()
            val segStops = ArrayList<TrackStop>()
            var i = 0
            while (i < idx.size - 1) {
                val a = seg[idx[i]]
                var j = i
                while (j + 1 < idx.size && distance3(a.lat, a.lon, a.ele, seg[idx[j + 1]]) <= options.radiusM) j++
                val t0 = a.time!!
                if (j > i && (seg[idx[j]].time!! - t0) / 1000.0 >= options.minStopS) {
                    // A stay: it goes on while the next point is within the radius of the stay's centre, so
                    // wandering about a rest spot (or the anchor having been on the approach) does not end it.
                    var latSum = 0.0
                    var lonSum = 0.0
                    var eleSum = 0.0
                    var eleCount = 0
                    fun add(p: TrackPoint) {
                        latSum += p.lat; lonSum += p.lon
                        p.ele?.let { eleSum += it; eleCount++ }
                    }
                    for (m in i..j) add(seg[idx[m]])
                    while (j + 1 < idx.size) {
                        val q = seg[idx[j + 1]]
                        val count = j - i + 1
                        if (distance3(latSum / count, lonSum / count, if (eleCount > 0) eleSum / eleCount else null, q) > options.radiusM) break
                        add(q)
                        j++
                    }
                    // The speed check: a slow walk round a hairpin also stays within the radius for a minute.
                    val speeds = (i..j).map { speed[idx[it]] }.filter { !it.isNaN() }.sorted()
                    if (speeds.isNotEmpty() && speeds[speeds.size / 2] > options.stillSpeedMps) { i++; continue }
                    segStops += TrackStop(si, idx[i], idx[j], t0, seg[idx[j]].time!!)
                    i = j + 1
                } else {
                    i++
                }
            }
            stops += segStops
            // The stretches between: from the segment's start or a stop's end to the next stop's start or the end.
            var from = idx.first()
            fun stretch(to: Int) {
                val t0 = seg[from].time!!
                val t1 = seg[to].time!!
                if (to > from && t1 > t0) stretches += TrackStretch(si, from, to, t0, t1, distance(seg, idx, from, to))
            }
            for (s in segStops) {
                stretch(s.startIndex)
                from = s.endIndex
            }
            stretch(idx.last())
        }
        return StopCut(stops, stretches)
    }

    /** From a position to [p], metres: the climb counts too where both have a height, so a slow steep pitch is no stay. */
    private fun distance3(lat: Double, lon: Double, ele: Double?, p: TrackPoint): Double {
        val h = TrackStats.distanceM(lat, lon, p.lat, p.lon)
        val dz = if (ele != null && p.ele != null) p.ele - ele else 0.0
        return kotlin.math.sqrt(h * h + dz * dz)
    }

    /** Distance along [seg]'s points in [idx] from raw index [from] to [to]. */
    private fun distance(seg: List<TrackPoint>, idx: List<Int>, from: Int, to: Int): Double {
        var d = 0.0
        var prev: TrackPoint? = null
        for (k in idx) {
            if (k < from) continue
            if (k > to) break
            val p = seg[k]
            prev?.let { d += TrackStats.distanceM(it.lat, it.lon, p.lat, p.lon) }
            prev = p
        }
        return d
    }
}
