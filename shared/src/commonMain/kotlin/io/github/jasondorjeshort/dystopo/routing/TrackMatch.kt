package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.gpx.StopOptions
import io.github.jasondorjeshort.dystopo.gpx.TrackSmoothing
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.gpx.TrackStops
import io.github.jasondorjeshort.dystopo.model.Track
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import kotlin.math.roundToLong

/** T164: a track point placed on the trail network: where, the network's height there (null: no DEM), how far it moved. */
data class Snapped(val lat: Double, val lon: Double, val eleM: Double?, val distanceM: Double)

/** T164: places a point on the network within [maxM], or null when no line is that close. */
fun interface TrackSnapper {
    fun snap(lat: Double, lon: Double, maxM: Double): Snapped?
}

/**
 * T164: a recorded track onto the trail network, cut into the pieces the pace fit reads ([PaceFits.fit]).
 *
 * 1. The stop cut ([TrackStops.cut], T171): stays of over a minute come out; what is left are moving stretches.
 * 2. Each stretch's points, smoothed ([TrackSmoothing]) and without the fixes flagged worse than the recording's
 *    accuracy bound (T158), are snapped onto the network ([TrackSnapper]: [RoutingGraph.nearest] for one graph,
 *    [Router.nearest]'s [EdgeSnap] over a router's tiles) within [toleranceM] of each point (twice its accuracy, 20 to
 *    50 m). A point off the network, or a jump of over [JUMP_M], ends a run.
 * 3. Each run is cut into pieces of [PIECE_M] along the smoothed line (a last piece of [MIN_PIECE_M] or more is kept):
 *    length along the line, grade from the network's heights at the two ends (the DEM the router's own heights come
 *    from; the track's smoothed heights where the network has none, or with no snapper at all), moving time from the
 *    point times.
 */
object TrackMatch {
    const val PIECE_M = 200.0
    const val MIN_PIECE_M = 50.0
    const val JUMP_M = 100.0

    fun toleranceM(p: TrackPoint): Double = (2 * (p.accuracyM ?: 10.0)).coerceIn(20.0, 50.0)

    /** Snaps onto one in-memory graph ([RoutingGraph.nearest]); the height is the edge's, between its two vertices. */
    fun snapper(g: RoutingGraph): TrackSnapper = TrackSnapper { lat, lon, maxM ->
        g.nearest(lat, lon, maxM)?.let { s -> Snapped(s.lat, s.lon, eleAt(g, s), s.distanceM) }
    }

    /** Snaps through a router's tiles ([Router.nearest]; [EdgeSnap.ele] carries the height). */
    fun snapper(r: Router): TrackSnapper = TrackSnapper { lat, lon, maxM ->
        r.nearest(lat, lon, maxM)?.let { s -> Snapped(s.lat, s.lon, s.ele, s.distanceM) }
    }

    /** The network's height at snap [s] on [g]: linear between the vertices of its segment; null where they have none. */
    fun eleAt(g: RoutingGraph, s: EdgeSnap): Double? {
        val e = s.edge
        val k = s.segment
        val cum = g.cumulative(e)
        val span = cum[k + 1] - cum[k]
        val t = if (span > 0) ((s.fraction * cum.last() - cum[k]) / span).coerceIn(0.0, 1.0) else 0.0
        val a = g.pointEle(e, k)
        val b = g.pointEle(e, k + 1)
        return when {
            a.isNaN() && b.isNaN() -> null
            a.isNaN() -> b.toDouble()
            b.isNaN() -> a.toDouble()
            else -> a + t * (b - a)
        }
    }

    /** What matching one track found: its pieces and the counts behind them. */
    data class Result(
        val pieces: List<PaceSample>,
        val points: Int,
        val matched: Int,
        val offNetwork: Int,
        val flagged: Int,
        val stops: Int,
        val stoppedS: Double,
        val movingS: Double,
        val movingM: Double,
        /** The median of the pieces' speeds (all moving stretches' when no piece), m/s: a mode for a track without one. */
        val medianMps: Double?,
    ) {
        val metres: Double get() = pieces.sumOf { it.lengthM }

        fun json(): Map<String, Any?> = linkedMapOf(
            "points" to points, "matched" to matched, "offNetwork" to offNetwork, "flagged" to flagged,
            "stops" to stops, "stoppedMin" to (stoppedS / 6).roundToLong() / 10.0, "movingMin" to (movingS / 6).roundToLong() / 10.0,
            "movingKm" to (movingM / 100).roundToLong() / 10.0, "pieces" to pieces.size, "pieceKm" to (metres / 100).roundToLong() / 10.0,
            "medianKmh" to medianMps?.let { (it * 36).roundToLong() / 10.0 },
        )
    }

    fun match(track: Track, snapper: TrackSnapper?, stops: StopOptions = StopOptions()): Result {
        val raw = track.segments.map { it.points }
        val cut = TrackStops.cut(raw, stops)
        val smoothed = stops.smoothing?.let { TrackSmoothing.smooth(raw, it) } ?: raw
        val pieces = ArrayList<PaceSample>()
        var matched = 0
        var off = 0
        var flagged = 0
        var movingS = 0.0
        var movingM = 0.0
        val stretchSpeeds = ArrayList<Double>()
        for (st in cut.stretches) {
            movingS += st.durationS
            movingM += st.distanceM
            if (st.durationS > 0) stretchSpeeds += st.distanceM / st.durationS
            val seg = smoothed[st.segment]
            val rs = raw[st.segment]
            // A run: consecutive matched points (lat, lon, height, time).
            var run = ArrayList<RunPoint>()
            fun flush() {
                cutRun(run, pieces)
                run = ArrayList()
            }
            for (i in st.startIndex..st.endIndex) {
                val p = seg[i]
                if (!TrackSmoothing.counts(rs[i])) { flagged++; continue }
                val time = p.time ?: continue
                val prev = run.lastOrNull()
                if (prev != null && TrackStats.distanceM(prev.lat, prev.lon, p.lat, p.lon) > JUMP_M) flush()
                val ele: Double?
                if (snapper != null) {
                    val s = snapper.snap(p.lat, p.lon, toleranceM(rs[i]))
                    if (s == null) { off++; flush(); continue }
                    matched++
                    ele = s.eleM ?: p.ele
                } else {
                    ele = p.ele
                }
                if (ele == null) { flush(); continue }
                run += RunPoint(p.lat, p.lon, ele, time)
            }
            flush()
        }
        val speeds = pieces.map { it.lengthM / it.timeS }.ifEmpty { stretchSpeeds }.sorted()
        val median = if (speeds.isEmpty()) null else speeds[speeds.size / 2]
        return Result(pieces, raw.sumOf { it.size }, matched, off, flagged, cut.stops.size, cut.stoppedS, movingS, movingM, median)
    }

    private class RunPoint(val lat: Double, val lon: Double, val ele: Double, val time: Long)

    /** Cuts one run into pieces of [PIECE_M] (a last one of [MIN_PIECE_M] or more kept) into [out]. */
    private fun cutRun(run: List<RunPoint>, out: MutableList<PaceSample>) {
        if (run.size < 2) return
        var from = 0
        var len = 0.0
        for (i in 1 until run.size) {
            len += TrackStats.distanceM(run[i - 1].lat, run[i - 1].lon, run[i].lat, run[i].lon)
            val last = i == run.size - 1
            if (len >= PIECE_M || (last && len >= MIN_PIECE_M)) {
                val dt = (run[i].time - run[from].time) / 1000.0
                if (dt > 0) out += PaceSample((run[i].ele - run[from].ele) / len, len, dt)
                from = i
                len = 0.0
            }
        }
    }
}

/**
 * T164: synthetic tracks walked at a known pace ([PaceFit]), for the round-trip test and `devtool pace-fit --synthesize`:
 * out along a line with heights and back, at the curve's speed for the grade over the next [LOOK_M] times a per-track
 * and a per-100 m factor (log-normal, [paceNoise]), one fix every [STEP_S] seconds with Gaussian position noise
 * ([gpsNoiseM]) and height noise, and a stop of [stopS] at the far end. Seeded, so the same arguments give the same track.
 * Never a recording: the name and the description say synthetic.
 */
object SyntheticWalk {
    const val STEP_S = 3.0
    const val LOOK_M = 20.0

    fun track(
        id: String, name: String, line: List<TrackPoint>, truth: PaceFit, startMs: Long, seed: Int,
        gpsNoiseM: Double = 3.0, paceNoise: Double = 0.04, stopS: Double = 300.0,
        activity: io.github.jasondorjeshort.dystopo.model.Activity? = io.github.jasondorjeshort.dystopo.model.Activity.FOOT,
    ): Track {
        require(line.size >= 2 && line.all { it.ele != null }) { "a line with heights" }
        val rnd = kotlin.random.Random(seed)
        fun gauss(): Double {
            val u = rnd.nextDouble().coerceAtLeast(1e-12)
            val v = rnd.nextDouble()
            return kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * kotlin.math.PI * v)
        }
        val cum = DoubleArray(line.size)
        for (i in 1 until line.size) cum[i] = cum[i - 1] + TrackStats.distanceM(line[i - 1].lat, line[i - 1].lon, line[i].lat, line[i].lon)
        val total = cum.last()
        fun at(s: Double): Triple<Double, Double, Double> {
            val d = s.coerceIn(0.0, total)
            var lo = 0
            var hi = line.size - 1
            while (lo < hi) { val mid = (lo + hi + 1) / 2; if (cum[mid] <= d) lo = mid else hi = mid - 1 }
            val k = lo.coerceAtMost(line.size - 2)
            val span = cum[k + 1] - cum[k]
            val f = if (span > 0) (d - cum[k]) / span else 0.0
            val a = line[k]
            val b = line[k + 1]
            return Triple(a.lat + f * (b.lat - a.lat), a.lon + f * (b.lon - a.lon), a.ele!! + f * (b.ele!! - a.ele))
        }
        val trackFactor = kotlin.math.exp(paceNoise * gauss())
        val pieceFactor = HashMap<Long, Double>()
        val pts = ArrayList<TrackPoint>()
        var t = startMs.toDouble()
        val mPerLat = 111_195.0
        fun emit(s: Double) {
            val (lat, lon, ele) = at(s)
            val mPerLon = mPerLat * kotlin.math.cos(lat * kotlin.math.PI / 180)
            pts += TrackPoint(
                lat + gauss() * gpsNoiseM / mPerLat, lon + gauss() * gpsNoiseM / mPerLon, ((ele + gauss() * 2.0) * 10).roundToLong() / 10.0,
                t.roundToLong(), accuracyM = 2 * gpsNoiseM + 2,
            )
        }
        for (dir in listOf(1, -1)) {
            var s = if (dir == 1) 0.0 else total
            while (if (dir == 1) s < total else s > 0) {
                emit(s)
                val ahead = (s + dir * LOOK_M).coerceIn(0.0, total)
                val run = kotlin.math.abs(ahead - s)
                val g = if (run > 1) (at(ahead).third - at(s).third) / run else 0.0
                val key = (s / 100).toLong() * 2 + (if (dir == 1) 0 else 1)
                val f = pieceFactor.getOrPut(key) { kotlin.math.exp(paceNoise * gauss()) }
                s += dir * truth.speedMps(g) * trackFactor * f * STEP_S
                t += STEP_S * 1000
            }
            if (dir == 1) {
                // The stop at the far end: fixes on the spot.
                var w = 0.0
                while (w < stopS) { emit(total); t += STEP_S * 1000; w += STEP_S }
            }
        }
        emit(0.0)
        return Track(
            id, name, listOf(io.github.jasondorjeshort.dystopo.model.TrackSegment(pts)),
            description = "Synthetic track (T164): walked at a known pace, not a recording", type = null, activity = activity,
        )
    }
}
