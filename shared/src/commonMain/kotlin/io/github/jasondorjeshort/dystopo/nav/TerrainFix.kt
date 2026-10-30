package io.github.jasondorjeshort.dystopo.nav

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.routing.ElevationSampler
import io.github.jasondorjeshort.dystopo.routing.RoutingGraph
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * T166: the filter's parameters. Every default is stated with its reason in the finding
 * `docs/findings/2026-10-30-terrain-matched-position-measurement.md`.
 */
data class TerrainFixOptions(
    val particles: Int = 2000,
    /** Per-axis error (m, one sigma) of the seed fix: particles start around it. */
    val seedSigmaM: Double = 8.0,
    /** A start point is snapped onto the nearest trail within this (m); farther draws are redrawn. */
    val seedSnapM: Double = 50.0,
    /** Prior spread (one sigma, as a fraction) of the stride scale each particle carries. */
    val strideSigma: Double = 0.15,
    /** Per-step noise on each particle's stride (fraction, one sigma). */
    val strideStepNoise: Double = 0.05,
    /** Random walk of the stride scale per square root of 100 m walked (fraction). */
    val strideWalk: Double = 0.03,
    /** Prior spread (m) of the barometer-minus-DEM offset around the one read at the seed. */
    val offsetSigmaM: Double = 3.0,
    /** Random walk of that offset (m per square root of an hour): the weather's drift. */
    val offsetWalkMPerSqrtH: Double = 6.0,
    /** Width (m, one sigma) of the elevation likelihood per independent look. */
    val eleSigmaM: Double = 6.0,
    /** Walking distance (m) between independent looks; a step's likelihood is tempered by stride / this. */
    val corrLengthM: Double = 50.0,
    /** Width (degrees) of the heading likelihood and its floor (a pocketed phone points anywhere sometimes). */
    val headingSigmaDeg: Double = 35.0,
    val headingFloor: Double = 0.1,
    /** Width (degrees) of the branch choice at a junction. */
    val junctionSigmaDeg: Double = 45.0,
    val useElevation: Boolean = true,
    val useHeading: Boolean = true,
    /** The spread is this weighted quantile of the particles' distance from the centre. */
    val spreadQuantile: Double = 0.9,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "particles" to particles, "seedSigmaM" to seedSigmaM, "seedSnapM" to seedSnapM, "strideSigma" to strideSigma,
        "strideStepNoise" to strideStepNoise, "strideWalk" to strideWalk, "offsetSigmaM" to offsetSigmaM,
        "offsetWalkMPerSqrtH" to offsetWalkMPerSqrtH, "eleSigmaM" to eleSigmaM, "corrLengthM" to corrLengthM,
        "headingSigmaDeg" to headingSigmaDeg, "headingFloor" to headingFloor, "junctionSigmaDeg" to junctionSigmaDeg,
        "useElevation" to useElevation, "useHeading" to useHeading, "spreadQuantile" to spreadQuantile,
    )
}

/** The filter's answer after a step: the cloud's weighted centre and the radius ([spreadM]) holding [TerrainFixOptions.spreadQuantile] of it. */
data class TerrainEstimate(val lat: Double, val lon: Double, val spreadM: Double, val ess: Double, val bestResidualM: Double)

/**
 * T166: a position from the terrain when GPS is gone (`docs/ideas/2026-10-29-terrain-matched-position-without-gps.md`).
 * A particle filter over [graph]'s edges near a seed fix: each particle walks along an edge at the measured
 * stride (times its own stride scale) and picks branches at junctions by the measured heading; it is weighted
 * by how the barometric altitude, less the particle's own barometer-minus-DEM offset, matches [sampler]'s
 * elevation where it stands (the offset random-walks, so the shape counts and the weather's slow drift does
 * not), and by how its direction of travel matches the heading. The cloud's weighted centre is the fix and the
 * radius holding [TerrainFixOptions.spreadQuantile] of its weight the accuracy. Pure Kotlin; off the graph the
 * filter has nothing (the grid variant is the live source's question).
 */
class TerrainFix(
    private val graph: RoutingGraph,
    private val sampler: ElevationSampler,
    val options: TerrainFixOptions = TerrainFixOptions(),
    private val random: Random = Random(1),
) {
    private val n = options.particles
    private val edge = IntArray(n)
    private val pos = DoubleArray(n) // metres from the edge's from node
    private val dir = IntArray(n) // +1 toward the to node, -1 toward the from node
    private val scale = DoubleArray(n)
    private val offset = DoubleArray(n)
    private val logW = DoubleArray(n)
    private val x = DoubleArray(n)
    private val y = DoubleArray(n)
    private val heading = DoubleArray(n)
    private val dem = DoubleArray(n)

    private var lat0 = 0.0
    private var lon0 = 0.0
    private var mPerLon = M_PER_LAT

    var resamples = 0
        private set
    var demSamples = 0L
        private set
    var started = false
        private set

    private class Geom(val x: DoubleArray, val y: DoubleArray, val cum: DoubleArray) {
        val length: Double get() = cum.last()
    }

    private val geoms = HashMap<Int, Geom>()
    private val startBearings = HashMap<Int, Double>()

    private fun geom(e: Int): Geom = geoms.getOrPut(e) {
        val k = graph.pointCount(e)
        val gx = DoubleArray(k) { (graph.pointLonE6(e, it) / RoutingGraph.E6 - lon0) * mPerLon }
        val gy = DoubleArray(k) { (graph.pointLatE6(e, it) / RoutingGraph.E6 - lat0) * M_PER_LAT }
        val cum = DoubleArray(k)
        for (i in 1 until k) cum[i] = cum[i - 1] + hypot(gx[i] - gx[i - 1], gy[i] - gy[i - 1])
        Geom(gx, gy, cum)
    }

    /** Bearing (deg) of the first ~20 m of edge [e] walked in direction [d]. */
    private fun startBearing(e: Int, d: Int): Double = startBearings.getOrPut(e * 2 + (if (d > 0) 0 else 1)) {
        val g = geom(e)
        val len = g.length
        val a = if (d > 0) 0.0 else len
        val b = if (d > 0) min(len, LOOKAHEAD_M) else max(0.0, len - LOOKAHEAD_M)
        val (ax, ay) = at(g, a)
        val (bx, by) = at(g, b)
        bearing(bx - ax, by - ay)
    }

    private fun at(g: Geom, s: Double): Pair<Double, Double> {
        val k = segment(g, s)
        val seg = g.cum[k + 1] - g.cum[k]
        val t = if (seg > 0) ((s - g.cum[k]) / seg).coerceIn(0.0, 1.0) else 0.0
        return (g.x[k] + t * (g.x[k + 1] - g.x[k])) to (g.y[k] + t * (g.y[k + 1] - g.y[k]))
    }

    private fun segment(g: Geom, s: Double): Int {
        var lo = 0
        var hi = g.cum.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (g.cum[mid] <= s) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun latOf(yy: Double) = lat0 + yy / M_PER_LAT
    private fun lonOf(xx: Double) = lon0 + xx / mPerLon

    private fun gauss(): Double {
        // Box-Muller; 1 - nextDouble() is in (0, 1].
        val u = 1.0 - random.nextDouble()
        val v = random.nextDouble()
        return sqrt(-2.0 * ln(u)) * cos(2 * PI * v)
    }

    private fun weightOfTurn(want: Double, have: Double, sigma: Double): Double {
        val d = angleDiff(want, have)
        return exp(-d * d / (2 * sigma * sigma))
    }

    /**
     * Seeds the cloud around the fix ([lat], [lon]) with the barometric altitude [baroM] read there; [courseDeg]
     * (the GPS course before it was lost, NaN when unknown) chooses each particle's direction along its edge.
     * Throws IllegalStateException when no trail is within [TerrainFixOptions.seedSnapM] of the fix.
     */
    fun start(lat: Double, lon: Double, baroM: Double, courseDeg: Double = Double.NaN) {
        lat0 = lat
        lon0 = lon
        mPerLon = M_PER_LAT * cos(lat * PI / 180)
        geoms.clear()
        startBearings.clear()
        val seedDem = sampler.elevation(lat, lon).also { demSamples++ }
        val base = if (seedDem.isNaN()) 0.0 else baroM - seedDem
        var misses = 0
        var i = 0
        while (i < n) {
            val px = gauss() * options.seedSigmaM
            val py = gauss() * options.seedSigmaM
            val snap = graph.nearest(latOf(py), lonOf(px), options.seedSnapM)
            if (snap == null) {
                if (++misses > 20 * n) throw IllegalStateException("no trail within ${options.seedSnapM} m of the seed fix")
                continue
            }
            val g = geom(snap.edge)
            edge[i] = snap.edge
            pos[i] = snap.fraction * g.length
            dir[i] = if (!courseDeg.isNaN()) {
                val fw = weightOfTurn(courseDeg, startBearing(snap.edge, 1), options.junctionSigmaDeg) + 0.02
                val bw = weightOfTurn(courseDeg, startBearing(snap.edge, -1), options.junctionSigmaDeg) + 0.02
                if (random.nextDouble() * (fw + bw) < fw) 1 else -1
            } else if (random.nextBoolean()) 1 else -1
            scale[i] = (1.0 + gauss() * options.strideSigma).coerceIn(0.5, 1.6)
            offset[i] = base + gauss() * options.offsetSigmaM
            logW[i] = 0.0
            place(i)
            i++
        }
        resamples = 0
        started = true
    }

    private fun place(i: Int) {
        val g = geom(edge[i])
        val k = segment(g, pos[i])
        val seg = g.cum[k + 1] - g.cum[k]
        val t = if (seg > 0) ((pos[i] - g.cum[k]) / seg).coerceIn(0.0, 1.0) else 0.0
        x[i] = g.x[k] + t * (g.x[k + 1] - g.x[k])
        y[i] = g.y[k] + t * (g.y[k + 1] - g.y[k])
        val b = bearing(g.x[k + 1] - g.x[k], g.y[k + 1] - g.y[k])
        heading[i] = if (dir[i] > 0) b else (b + 180) % 360
    }

    /** Walks particle [i] [m] metres along the network, choosing branches by [headingDeg] (NaN: at random). */
    private fun move(i: Int, m0: Double, headingDeg: Double) {
        var m = m0
        var guard = 0
        while (m > 0 && guard++ < 64) {
            val g = geom(edge[i])
            val left = if (dir[i] > 0) g.length - pos[i] else pos[i]
            if (m <= left) {
                pos[i] += dir[i] * m
                break
            }
            m -= left
            val e = edge[i]
            val node = if (dir[i] > 0) graph.edgeTo[e] else graph.edgeFrom[e]
            val arrivedBack = if (dir[i] > 0) e * 2 + 1 else e * 2 // the entry that would walk straight back
            val a0 = graph.adjStart[node]
            val a1 = graph.adjStart[node + 1]
            var total = 0.0
            var count = 0
            for (a in a0 until a1) {
                if (graph.adjEdge[a] == arrivedBack && a1 - a0 > 1) continue
                total += turnWeight(graph.adjEdge[a], headingDeg)
                count++
            }
            var pick = graph.adjEdge[a0]
            if (count > 0) {
                var r = random.nextDouble() * total
                for (a in a0 until a1) {
                    if (graph.adjEdge[a] == arrivedBack && a1 - a0 > 1) continue
                    pick = graph.adjEdge[a]
                    r -= turnWeight(pick, headingDeg)
                    if (r <= 0) break
                }
            }
            val e2 = pick / 2
            val d2 = if (pick % 2 == 0) 1 else -1
            edge[i] = e2
            dir[i] = d2
            pos[i] = if (d2 > 0) 0.0 else geom(e2).length
        }
        val len = geom(edge[i]).length
        pos[i] = pos[i].coerceIn(0.0, len)
        place(i)
    }

    private fun turnWeight(entry: Int, headingDeg: Double): Double =
        if (headingDeg.isNaN() || !options.useHeading) 1.0
        else weightOfTurn(headingDeg, startBearing(entry / 2, if (entry % 2 == 0) 1 else -1), options.junctionSigmaDeg) + 0.02

    /**
     * One step: the walker went [strideM] metres (the step counter's distance) toward [headingDeg] (NaN when
     * unknown) and the barometer reads [baroM]; [dtS] seconds passed. Returns the new estimate.
     */
    fun step(strideM: Double, headingDeg: Double, baroM: Double, dtS: Double): TerrainEstimate {
        check(started) { "start() first" }
        val alpha = min(1.0, max(0.0, strideM) / options.corrLengthM)
        val offsetStep = options.offsetWalkMPerSqrtH * sqrt(max(0.0, dtS) / 3600.0)
        val scaleStep = options.strideWalk * sqrt(max(0.0, strideM) / 100.0)
        val hSigma2 = 2 * options.headingSigmaDeg * options.headingSigmaDeg
        val eSigma2 = 2 * options.eleSigmaM * options.eleSigmaM
        var bestResidual = Double.MAX_VALUE
        for (i in 0 until n) {
            if (strideM > 0) move(i, strideM * scale[i] * (1 + gauss() * options.strideStepNoise), headingDeg)
            scale[i] = (scale[i] + gauss() * scaleStep).coerceIn(0.5, 1.6)
            offset[i] += gauss() * offsetStep
            if (alpha <= 0) continue
            if (options.useElevation && !baroM.isNaN()) {
                val h = sampler.elevation(latOf(y[i]), lonOf(x[i]))
                demSamples++
                dem[i] = h
                if (!h.isNaN()) {
                    val r = baroM - offset[i] - h
                    if (abs(r) < bestResidual) bestResidual = abs(r)
                    logW[i] -= alpha * r * r / eSigma2
                }
            }
            if (options.useHeading && !headingDeg.isNaN()) {
                val d = angleDiff(headingDeg, heading[i])
                logW[i] += alpha * ln(options.headingFloor + (1 - options.headingFloor) * exp(-d * d / hSigma2))
            }
        }
        val ess = normalize()
        if (ess < n * RESAMPLE_ESS) {
            resample()
            resamples++
        }
        return estimate(ess, if (bestResidual == Double.MAX_VALUE) Double.NaN else bestResidual)
    }

    /** Shifts the log weights so the largest is 0; returns the effective sample size. */
    private fun normalize(): Double {
        var top = Double.NEGATIVE_INFINITY
        for (i in 0 until n) if (logW[i] > top) top = logW[i]
        var sum = 0.0
        var sum2 = 0.0
        for (i in 0 until n) {
            logW[i] -= top
            val w = exp(logW[i])
            sum += w
            sum2 += w * w
        }
        return sum * sum / sum2
    }

    private fun weights(): DoubleArray {
        val w = DoubleArray(n) { exp(logW[it]) }
        val s = w.sum()
        for (i in 0 until n) w[i] /= s
        return w
    }

    /** Systematic resampling: [n] evenly spaced draws over the cumulative weight. */
    private fun resample() {
        val w = weights()
        val pick = IntArray(n)
        val u0 = random.nextDouble() / n
        var c = w[0]
        var j = 0
        for (i in 0 until n) {
            val u = u0 + i.toDouble() / n
            while (u > c && j < n - 1) { j++; c += w[j] }
            pick[i] = j
        }
        val e = edge.copyOf(); val p = pos.copyOf(); val d = dir.copyOf(); val sc = scale.copyOf(); val o = offset.copyOf()
        val xx = x.copyOf(); val yy = y.copyOf(); val hh = heading.copyOf()
        for (i in 0 until n) {
            val k = pick[i]
            edge[i] = e[k]; pos[i] = p[k]; dir[i] = d[k]; scale[i] = sc[k]; offset[i] = o[k]
            x[i] = xx[k]; y[i] = yy[k]; heading[i] = hh[k]; logW[i] = 0.0
        }
    }

    /** The current estimate without stepping. */
    fun estimate(): TerrainEstimate = estimate(normalize(), Double.NaN)

    private fun estimate(ess: Double, bestResidual: Double): TerrainEstimate {
        val w = weights()
        var cx = 0.0
        var cy = 0.0
        for (i in 0 until n) { cx += w[i] * x[i]; cy += w[i] * y[i] }
        val dist = DoubleArray(n) { hypot(x[it] - cx, y[it] - cy) }
        val order = (0 until n).sortedBy { dist[it] }
        var acc = 0.0
        var spread = 0.0
        for (i in order) {
            acc += w[i]
            spread = dist[i]
            if (acc >= options.spreadQuantile) break
        }
        return TerrainEstimate(latOf(cy), lonOf(cx), spread, ess, bestResidual)
    }

    companion object {
        const val M_PER_LAT = 111_195.0
        private const val LOOKAHEAD_M = 20.0
        private const val RESAMPLE_ESS = 0.5

        fun hypot(a: Double, b: Double) = sqrt(a * a + b * b)

        /** Bearing (deg, clockwise from north) of a step of [dx] east and [dy] north. */
        fun bearing(dx: Double, dy: Double): Double = (atan2(dx, dy) * 180 / PI + 360) % 360

        /** Smallest difference between two bearings, 0..180. */
        fun angleDiff(a: Double, b: Double): Double {
            val d = abs((a - b) % 360)
            return if (d > 180) 360 - d else d
        }
    }
}

/**
 * T166: the measurement. Replays a walked line (a GPX track, or a route walked at Tobler's pace) as the phone's
 * sensors would see it, cuts the GPS over a stretch and runs [TerrainFix] through it, comparing each estimate
 * with the true position. The sensors are simulated from the line: the barometer is its elevation plus a
 * weather drift and noise, the step counter its spacing with a stride bias that grows with the grade, the
 * compass its bearing with a bias and noise, the seed the true position at the cut plus a GPS error.
 */
object TerrainFixReplay {
    /** One point of the walked line (time in seconds from its start, elevation in metres). */
    data class LinePoint(val lat: Double, val lon: Double, val ele: Double, val timeS: Double)

    /** The truth at one replay step. */
    data class Truth(val timeS: Double, val lat: Double, val lon: Double, val ele: Double, val alongM: Double)

    data class Sensors(
        /** The step counter's distance is the true one times (1 + bias)... */
        val strideBias: Double = 0.07,
        /** ...times (1 + this * |grade|): strides shorten on slopes while the counted length stays. */
        val strideGrade: Double = 0.5,
        val strideNoise: Double = 0.05,
        val headingBiasDeg: Double = 5.0,
        val headingNoiseDeg: Double = 15.0,
        val baroNoiseM: Double = 0.5,
        val baroDriftMPerH: Double = 4.0,
        /** Per-axis error (m) of the seed fix. */
        val seedErrorM: Double = 8.0,
    ) {
        fun json(): Map<String, Any?> = linkedMapOf(
            "strideBias" to strideBias, "strideGrade" to strideGrade, "strideNoise" to strideNoise,
            "headingBiasDeg" to headingBiasDeg, "headingNoiseDeg" to headingNoiseDeg, "baroNoiseM" to baroNoiseM,
            "baroDriftMPerH" to baroDriftMPerH, "seedErrorM" to seedErrorM,
        )
    }

    /** Times a line without them by Tobler's hiking function (6 e^(-3.5 |grade + 0.05|) km/h). */
    fun toblerTimes(points: List<Triple<Double, Double, Double>>): List<LinePoint> {
        var t = 0.0
        val out = ArrayList<LinePoint>(points.size)
        for ((k, p) in points.withIndex()) {
            if (k > 0) {
                val q = points[k - 1]
                val d = TrackStats.distanceM(q.first, q.second, p.first, p.second)
                val grade = if (d > 0.5) (p.third - q.third) / d else 0.0
                val v = 6.0 * exp(-3.5 * abs(grade + 0.05)) / 3.6
                t += d / v
            }
            out += LinePoint(p.first, p.second, p.third, t)
        }
        return out
    }

    /** The line resampled every [stepS] seconds (linear between points), with the distance walked. */
    fun resample(line: List<LinePoint>, stepS: Double): List<Truth> {
        require(line.size >= 2) { "a line needs two points" }
        val along = DoubleArray(line.size)
        for (k in 1 until line.size) along[k] = along[k - 1] + TrackStats.distanceM(line[k - 1].lat, line[k - 1].lon, line[k].lat, line[k].lon)
        val t0 = line.first().timeS
        val out = ArrayList<Truth>()
        var k = 0
        var t = t0
        while (t <= line.last().timeS) {
            while (k < line.size - 2 && line[k + 1].timeS < t) k++
            val a = line[k]
            val b = line[k + 1]
            val f = if (b.timeS > a.timeS) ((t - a.timeS) / (b.timeS - a.timeS)).coerceIn(0.0, 1.0) else 1.0
            out += Truth(t - t0, a.lat + f * (b.lat - a.lat), a.lon + f * (b.lon - a.lon), a.ele + f * (b.ele - a.ele), along[k] + f * (along[k + 1] - along[k]))
            t += stepS
        }
        return out
    }

    /** One run over one cut: per step the error, the spread and whether the spread covered the error. */
    class Run(val errors: DoubleArray, val spreads: DoubleArray, val along: DoubleArray, val resamples: Int, val demSamples: Long, val poorFit: Int)

    /**
     * Runs the filter over the steps of [truth] whose distance walked lies in [fromM]..[toM] (the GPS cut),
     * seeded at the step before the cut; [seed] makes the sensor noise and the filter repeatable.
     */
    fun runCut(
        graph: RoutingGraph, sampler: ElevationSampler, truth: List<Truth>, fromM: Double, toM: Double,
        options: TerrainFixOptions = TerrainFixOptions(), sensors: Sensors = Sensors(), seed: Int = 1,
    ): Run {
        val first = truth.indexOfFirst { it.alongM >= fromM }.let { if (it < 0) throw IllegalArgumentException("the cut starts beyond the line's end") else it }
        val last = truth.indexOfLast { it.alongM <= toM }
        require(last > first) { "the cut $fromM..$toM m holds no step" }
        val rnd = Random(seed * 7919 + 17)
        fun g() = sqrt(-2.0 * ln(1.0 - rnd.nextDouble())) * cos(2 * PI * rnd.nextDouble())
        val t0 = truth[first].timeS
        fun baro(p: Truth) = p.ele + sensors.baroDriftMPerH * (p.timeS - t0) / 3600.0 + g() * sensors.baroNoiseM
        val s = truth[first]
        val mPerLon = TerrainFix.M_PER_LAT * cos(s.lat * PI / 180)
        val seedLat = s.lat + g() * sensors.seedErrorM / TerrainFix.M_PER_LAT
        val seedLon = s.lon + g() * sensors.seedErrorM / mPerLon
        // The GPS course before the cut: the bearing over about the last 30 m walked.
        val back = (first downTo 0).firstOrNull { s.alongM - truth[it].alongM >= 30.0 }
        val course = back?.let { TrackStats.bearingDeg(truth[it].lat, truth[it].lon, s.lat, s.lon) } ?: Double.NaN
        val fix = TerrainFix(graph, sampler, options, Random(seed))
        fix.start(seedLat, seedLon, baro(s), course)
        val steps = last - first
        val errors = DoubleArray(steps)
        val spreads = DoubleArray(steps)
        val along = DoubleArray(steps)
        var poorFit = 0
        for (k in 1..steps) {
            val a = truth[first + k - 1]
            val b = truth[first + k]
            val d = b.alongM - a.alongM
            val grade = if (d > 0.5) abs(b.ele - a.ele) / d else 0.0
            val stride = max(0.0, d * (1 + sensors.strideBias) * (1 + sensors.strideGrade * grade) * (1 + g() * sensors.strideNoise))
            val heading = if (d > 0.5) (TrackStats.bearingDeg(a.lat, a.lon, b.lat, b.lon) + sensors.headingBiasDeg + g() * sensors.headingNoiseDeg + 360) % 360 else Double.NaN
            val est = fix.step(if (d > 0.5) stride else 0.0, heading, baro(b), b.timeS - a.timeS)
            if (!est.bestResidualM.isNaN() && est.bestResidualM > 3 * options.eleSigmaM) poorFit++
            errors[k - 1] = TrackStats.distanceM(est.lat, est.lon, b.lat, b.lon)
            spreads[k - 1] = est.spreadM
            along[k - 1] = b.alongM - s.alongM
        }
        return Run(errors, spreads, along, fix.resamples, fix.demSamples, poorFit)
    }

    /** Terrain of the cut: length, ascent, descent, relief and mean absolute grade from the truth's elevations. */
    fun terrain(truth: List<Truth>, fromM: Double, toM: Double): Map<String, Any?> {
        val pts = truth.filter { it.alongM in fromM..toM }
        if (pts.size < 2) return linkedMapOf("lengthM" to 0)
        var up = 0.0
        var down = 0.0
        var gradeSum = 0.0
        var lenSum = 0.0
        for ((a, b) in pts.zipWithNext()) {
            val dz = b.ele - a.ele
            if (dz > 0) up += dz else down -= dz
            val d = b.alongM - a.alongM
            if (d > 0.5) { gradeSum += abs(dz); lenSum += d }
        }
        return linkedMapOf(
            "lengthM" to r0(pts.last().alongM - pts.first().alongM), "durationS" to r0(pts.last().timeS - pts.first().timeS),
            "ascentM" to r0(up), "descentM" to r0(down), "reliefM" to r0(pts.maxOf { it.ele } - pts.minOf { it.ele }),
            "meanAbsGradePct" to if (lenSum > 0) r1(100 * gradeSum / lenSum) else 0.0,
        )
    }

    /** Pooled statistics over [runs]: median and p90 error, coverage, and the spread; [samples] timeline rows from the first run. */
    fun summary(runs: List<Run>, samples: Int = 12): Map<String, Any?> {
        val errors = runs.flatMap { it.errors.toList() }.sorted()
        val covered = runs.sumOf { r -> r.errors.indices.count { r.errors[it] <= r.spreads[it] } }
        val spreads = runs.flatMap { it.spreads.toList() }.sorted()
        val first = runs.first()
        val every = max(1, first.errors.size / max(1, samples))
        return linkedMapOf(
            "steps" to first.errors.size, "runs" to runs.size,
            "medianM" to r1(quantile(errors, 0.5)), "p90M" to r1(quantile(errors, 0.9)), "maxM" to r1(errors.last()),
            "coverage" to r3(covered.toDouble() / errors.size),
            "medianSpreadM" to r1(quantile(spreads, 0.5)),
            "finalErrorM" to runs.map { r1(it.errors.last()) }, "finalSpreadM" to runs.map { r1(it.spreads.last()) },
            "resamples" to runs.map { it.resamples }, "poorFitSteps" to runs.map { it.poorFit },
            "timeline" to first.errors.indices.filter { it % every == 0 || it == first.errors.size - 1 }.map {
                linkedMapOf("alongM" to r0(first.along[it]), "errorM" to r1(first.errors[it]), "spreadM" to r1(first.spreads[it]))
            },
        )
    }

    fun quantile(sorted: List<Double>, q: Double): Double = if (sorted.isEmpty()) Double.NaN else sorted[min(sorted.size - 1, (q * sorted.size).toInt())]

    private fun r0(v: Double) = v.roundToLong()
    private fun r1(v: Double) = (v * 10).roundToLong() / 10.0
    private fun r3(v: Double) = (v * 1000).roundToLong() / 1000.0
}
