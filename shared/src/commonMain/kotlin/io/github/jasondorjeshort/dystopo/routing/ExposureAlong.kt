package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.Slope
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.round
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * T195: the ridge rule. A point is on a ridge (or a summit, a knoll, a crest) when the ground there stands more than
 * [THRESHOLD_M] above the mean of the ground on a ring [RING_M] around it: a topographic position index (TPI) of one
 * scale. On an even slope the ring's mean equals the centre (TPI 0), in a valley or a gully it is above it (negative).
 *
 * - [RING_M] 150 m (the plan's figure, kept): the scale of a walker's crest, the high ground within a few minutes'
 *   walk. At z14 (about 6.5 m a pixel at Rainier) the ring is some 23 pixels out, far beyond the DEM's noise and the
 *   trail's own bench; a much larger ring measures the valley-to-summit scale instead, where every convex shoulder of
 *   a mountainside stands above its ring.
 * - [THRESHOLD_M] 15 m, tuned on Rainier (`devtool exposure --ridge-map --bbox=-121.765,46.775,-121.70,46.815`, the
 *   box round Paradise, maps at 10, 15 and 20 m viewed): at 10 m 27 % of the box is marked and the bands spread down
 *   the crests' flanks and join across the benches between them; at 20 m (14 %) the long crests (the one the Skyline
 *   Trail climbs to Panorama Point among them) break into pieces; at 15 m (20 %) each crest is one continuous band a
 *   few tens of metres wide. A crest whose sides fall at about 10 degrees on both sides passes (its ring's mean is
 *   some 17 m below it: 150 m times tan 10 degrees times 2 / pi), an even slope of any steepness never does.
 * - [RING_POINTS] samples on the ring (every 22.5 degrees); a ring with fewer than [MIN_RING_POINTS] of them inside
 *   the DEM gives NaN, never a ridge.
 *
 * The sampler should read the pack at z14 ([DemElevation] with `zoom = 14`). T196 reuses this rule for the walker's
 * position ("on a ridge or summit"); change the figures here and both follow.
 */
object RidgeRule {
    const val RING_M = 150.0
    const val THRESHOLD_M = 15.0
    const val RING_POINTS = 16
    const val MIN_RING_POINTS = 12

    /** The TPI at ([lat], [lon]) in metres: the ground there minus the mean of its ring; NaN without enough data. */
    fun tpiM(dem: ElevationSampler, lat: Double, lon: Double, centreM: Double = dem.elevation(lat, lon), ringM: Double = RING_M): Double {
        if (centreM.isNaN()) return Double.NaN
        var sum = 0.0
        var n = 0
        for (k in 0 until RING_POINTS) {
            val bearing = 2 * PI * k / RING_POINTS
            val (la, lo) = offset(lat, lon, ringM * sin(bearing), ringM * cos(bearing))
            val e = dem.elevation(la, lo)
            if (!e.isNaN()) { sum += e; n++ }
        }
        if (n < MIN_RING_POINTS) return Double.NaN
        return centreM - sum / n
    }

    /** True when ([lat], [lon]) is on a ridge or a summit by this rule (more than [THRESHOLD_M] above its ring). */
    fun onRidge(dem: ElevationSampler, lat: Double, lon: Double): Boolean = isRidge(tpiM(dem, lat, lon))

    /** True for a TPI ([tpiM]) above [THRESHOLD_M]; false for NaN. */
    fun isRidge(tpiM: Double): Boolean = !tpiM.isNaN() && tpiM > THRESHOLD_M

    private const val EARTH_M = 6378137.0

    /** The point [eastM] east and [northM] north of ([lat], [lon]) (local flat earth; exact enough within a few km). */
    fun offset(lat: Double, lon: Double, eastM: Double, northM: Double): Pair<Double, Double> {
        val dLat = northM / EARTH_M * 180.0 / PI
        val dLon = eastM / (EARTH_M * cos(lat * PI / 180.0)) * 180.0 / PI
        return (lat + dLat) to (lon + dLon)
    }
}

/**
 * T195: the numbers of [ExposureAlong.find].
 *
 * - [sampleM] 25 m between samples (the plan's figure): a step shorter than the 20 m footprint of the slope window
 *   would read the same ground twice, a longer one skips short steep pitches. Each step's length and time count in
 *   the class read at its middle.
 * - [zoom] 14 and [tileSize] 256: the slope window is Horn's 3x3 stencil (the slope overlay's) with its samples one
 *   z14 pixel apart, about 6.5 m at Rainier, so the window spans some 13 m by 13 m around the sample. That is the
 *   ground's slope, not the trail's grade: a cut trail bench is 1 to 2 m wide, under what a 10 m elevation source
 *   resolves, so the window reads the hillside the trail crosses; the same stencil and spacing as the overlay drawn at
 *   z14, so the card and the colours agree. A wider window (30 m) smooths short pitches under 30 degrees.
 * - [minSideM] 50 m: a compass side is named on the card when at least this much of the steep ground faces it (two
 *   samples), so one noisy sample does not add a side; the largest side is named when none reaches it.
 */
data class ExposureParams(
    val sampleM: Double = 25.0,
    val zoom: Int = 14,
    val tileSize: Int = 256,
    val minSideM: Double = 50.0,
) {
    init {
        require(sampleM > 0 && minSideM >= 0 && zoom in 0..20 && tileSize > 0) { "bad exposure parameters" }
    }

    /** The slope window's sample spacing at [lat]: one pixel of [zoom], metres. */
    fun cellM(lat: Double): Double = 2 * PI * 6378137.0 * cos(lat * PI / 180.0) / (tileSize.toDouble() * (1L shl zoom))
}

/** T195: a band of ground along the route: its total distance and time (the route's pace). */
data class ExposureBand(val distanceM: Double, val timeS: Double) {
    fun json(): Map<String, Any?> = linkedMapOf("distanceM" to r1(distanceM), "timeS" to r1(timeS))
}

/**
 * T195: one step of the route ([ExposureParams.sampleM] long, the last one shorter), read at its middle.
 * [slopeDeg] and [aspectDeg] are the ground's (NaN without data; the aspect NaN on flat ground), [side] the index of
 * [Slope.COMPASS_SIDES] (-1 for none), [tpiM] [RidgeRule.tpiM], [eleM] the ground at the step's middle.
 */
data class ExposureSample(
    val fromM: Double,
    val toM: Double,
    val lat: Double,
    val lon: Double,
    val eleM: Double,
    val slopeDeg: Double,
    val aspectDeg: Double,
    val side: Int,
    val tpiM: Double,
    val ridge: Boolean,
    val timeS: Double,
) {
    val lengthM: Double get() = toM - fromM
    val steep: Boolean get() = !slopeDeg.isNaN() && slopeDeg >= Slope.STEEP_DEG
    val verySteep: Boolean get() = !slopeDeg.isNaN() && slopeDeg >= Slope.VERY_STEEP_DEG
}

/** T195: what an [ExposureStretch] is. [key] is the JSON value. */
enum class StretchKind(val key: String) { SLOPE("slope"), RIDGE("ridge") }

/**
 * T195: a run of steps on ground over 30 degrees ([StretchKind.SLOPE]) or on a ridge ([StretchKind.RIDGE]), from
 * [fromM] to [toM] along the route; runs one step apart are one stretch, so [distanceM] (the steps that qualify) can
 * be shorter than the span. [verySteepM] the part over 35 degrees, [maxSlopeDeg] the steepest step, [sides] the
 * compass sides of its steep steps (indexes of [Slope.COMPASS_SIDES], most first).
 */
data class ExposureStretch(
    val kind: StretchKind,
    val fromM: Double,
    val toM: Double,
    val distanceM: Double,
    val timeS: Double,
    val verySteepM: Double,
    val maxSlopeDeg: Double,
    val sides: List<Int>,
    val lat: Double,
    val lon: Double,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "kind" to kind.key, "fromM" to r1(fromM), "toM" to r1(toM), "distanceM" to r1(distanceM), "timeS" to r1(timeS),
        "over35M" to if (kind == StretchKind.SLOPE) r1(verySteepM) else null,
        "maxSlopeDeg" to if (maxSlopeDeg.isNaN()) null else r1(maxSlopeDeg),
        "facing" to sides.map { Slope.COMPASS_SIDES[it] }, "lat" to r6(lat), "lon" to r6(lon),
    )
}

/** T195: a route's terrain from [ExposureAlong.find]. */
class ExposureResult(
    val samples: List<ExposureSample>,
    val lengthM: Double,
    val params: ExposureParams,
    val mode: TravelMode,
) {
    /** The whole route's time on the same pace, seconds. */
    val totalTimeS: Double = samples.sumOf { it.timeS }

    /** Ground of 30 to 34 degrees, of 35 and over, and both (over 30). */
    val steep: ExposureBand = band { it.steep && !it.verySteep }
    val verySteep: ExposureBand = band { it.verySteep }
    val over30: ExposureBand = band { it.steep }

    /** Steps on a ridge by [RidgeRule]. */
    val ridge: ExposureBand = band { it.ridge }

    /** Steps without elevation data (outside the pack): counted in no band. */
    val noData: ExposureBand = band { it.slopeDeg.isNaN() }

    /** True when some step had elevation data (else there is nothing to say). */
    val hasData: Boolean get() = noData.distanceM < lengthM - 1e-6 && samples.isNotEmpty()

    /** Distance and time of the ground over 30 degrees by compass side ([Slope.COMPASS_SIDES]). */
    val sides: List<ExposureBand> = (0 until 8).map { s -> band { it.steep && it.side == s } }

    /** The runs of steep ground and of ridge, in route order (slope first where both start at one step). */
    val stretches: List<ExposureStretch> = (runs(StretchKind.SLOPE) { it.steep } + runs(StretchKind.RIDGE) { it.ridge })
        .sortedWith(compareBy<ExposureStretch> { it.fromM }.thenBy { it.kind.ordinal })

    /** The sides the card names: those with at least [ExposureParams.minSideM] (else the largest), clockwise from north. */
    val shownSides: List<Int>
        get() {
            if (over30.distanceM <= 0) return emptyList()
            val big = (0 until 8).filter { sides[it].distanceM >= params.minSideM - 1e-6 }
            return big.ifEmpty { listOf((0 until 8).maxBy { sides[it].distanceM }) }
        }

    private fun band(test: (ExposureSample) -> Boolean): ExposureBand {
        var d = 0.0
        var t = 0.0
        for (s in samples) if (test(s)) { d += s.lengthM; t += s.timeS }
        return ExposureBand(d, t)
    }

    private fun runs(kind: StretchKind, test: (ExposureSample) -> Boolean): List<ExposureStretch> {
        val out = ArrayList<ExposureStretch>()
        var i = 0
        while (i < samples.size) {
            if (!test(samples[i])) { i++; continue }
            var j = i // the last step of the run
            while (true) {
                val next = (j + 1 until minOf(samples.size, j + 3)).firstOrNull { test(samples[it]) } ?: break
                j = next // one step without, then one with: the same stretch
            }
            val run = samples.subList(i, j + 1).filter(test)
            val sideM = DoubleArray(8)
            for (s in run) if (s.steep && s.side >= 0) sideM[s.side] += s.lengthM
            val mid = run[run.size / 2]
            out += ExposureStretch(
                kind, samples[i].fromM, samples[j].toM, run.sumOf { it.lengthM }, run.sumOf { it.timeS },
                run.filter { it.verySteep }.sumOf { it.lengthM },
                run.mapNotNull { s -> s.slopeDeg.takeUnless { it.isNaN() } }.maxOrNull() ?: Double.NaN,
                (0 until 8).filter { sideM[it] > 0 }.sortedByDescending { sideM[it] }, mid.lat, mid.lon,
            )
            i = j + 1
        }
        return out
    }

    fun json(units: ContourUnits): Map<String, Any?> = linkedMapOf(
        "lengthM" to r1(lengthM), "mode" to mode.label, "totalTimeS" to r1(totalTimeS), "samples" to samples.size,
        "sampleM" to params.sampleM, "zoom" to params.zoom, "cellM" to samples.firstOrNull()?.let { r1(params.cellM(it.lat) * 10) / 10 },
        "hasData" to hasData, "noDataM" to r1(noData.distanceM),
        "bands" to listOf(
            linkedMapOf("key" to "30to34", "fromDeg" to Slope.STEEP_DEG, "toDeg" to Slope.VERY_STEEP_DEG) + steep.json(),
            linkedMapOf("key" to "35up", "fromDeg" to Slope.VERY_STEEP_DEG, "toDeg" to null) + verySteep.json(),
        ),
        "over30" to over30.json(), "over35" to verySteep.json(),
        "facing" to (0 until 8).filter { sides[it].distanceM > 0 }.map { linkedMapOf("side" to Slope.COMPASS_SIDES[it]) + sides[it].json() },
        "facingShown" to shownSides.map { Slope.COMPASS_SIDES[it] },
        "ridge" to ridge.json() + linkedMapOf("ringM" to RidgeRule.RING_M, "thresholdM" to RidgeRule.THRESHOLD_M),
        "card" to ExposureText.cardLine(this, units), "times" to ExposureText.timeLine(this), "ridgeLine" to ExposureText.ridgeLine(this, units),
        "help" to ExposureText.HELP,
        "stretches" to stretches.mapIndexed { i, s -> linkedMapOf<String, Any?>("n" to i + 1) + s.json() + linkedMapOf("row" to ExposureText.row(s, units)) },
    )
}

/**
 * T195: a planned route's terrain, computed on demand from the pack's DEM (nothing stored, no format change): the
 * route is cut into steps of [ExposureParams.sampleM]; at each step's middle the ground's slope and aspect come from
 * [Slope.hornAt] on a 3x3 window one z14 pixel apart ([Slope.degrees], [Slope.aspect]), and the ridge test from
 * [RidgeRule]; each step's time is the route's pace over it ([CostModel.timeS] of its length, climb and descent, the
 * ground's heights at its ends), as the card times the whole route. [ExposureResult] totals the distance and time on
 * ground of 30 to 34 degrees and of 35 and over, the compass sides of that ground, the time on ridges, and the
 * stretches. Terrain facts only: nothing here rates safety or conditions (the survey's words, [ExposureText]).
 */
object ExposureAlong {
    /**
     * The terrain along [route] over [dem] (the pack's DEM read at [ExposureParams.zoom]: [DemElevation] with
     * `zoom = 14`), timed by [model] (the route's mode; the walker's fitted pace when one is active).
     */
    fun find(route: List<RoutePoint>, dem: ElevationSampler, model: CostModel = CostModel(TravelMode.HIKE), params: ExposureParams = ExposureParams()): ExposureResult {
        if (route.size < 2) return ExposureResult(emptyList(), 0.0, params, model.mode)
        val cum = DoubleArray(route.size)
        for (i in 1 until route.size) cum[i] = cum[i - 1] + TrackStats.distanceM(route[i - 1].lat, route[i - 1].lon, route[i].lat, route[i].lon)
        val lengthM = cum.last()
        if (lengthM <= 0) return ExposureResult(emptyList(), 0.0, params, model.mode)
        var seg = 0
        fun at(m: Double): Pair<Double, Double> {
            while (seg < route.size - 2 && cum[seg + 1] < m) seg++
            while (seg > 0 && cum[seg] > m) seg--
            val a = route[seg]
            val b = route[seg + 1]
            val d = cum[seg + 1] - cum[seg]
            val t = if (d > 0) ((m - cum[seg]) / d).coerceIn(0.0, 1.0) else 0.0
            return (a.lat + (b.lat - a.lat) * t) to (a.lon + (b.lon - a.lon) * t)
        }
        val steps = kotlin.math.ceil(lengthM / params.sampleM - 1e-9).toInt().coerceAtLeast(1)
        val edges = DoubleArray(steps + 1) { minOf(it * params.sampleM, lengthM) }
        edges[steps] = lengthM
        val edgeEle = DoubleArray(steps + 1) { k -> at(edges[k]).let { (la, lo) -> dem.elevation(la, lo) } }
        val out = ArrayList<ExposureSample>(steps)
        for (k in 0 until steps) {
            val from = edges[k]
            val to = edges[k + 1]
            val (lat, lon) = at((from + to) / 2)
            val (slope, aspect, ele) = ground(dem, lat, lon, params.cellM(lat))
            val tpi = RidgeRule.tpiM(dem, lat, lon, ele)
            val dz = edgeEle[k + 1] - edgeEle[k]
            val up = if (dz.isNaN()) 0.0 else max(0.0, dz)
            val down = if (dz.isNaN()) 0.0 else max(0.0, -dz)
            val time = model.timeS(to - from, up, down)
            out += ExposureSample(from, to, lat, lon, ele, slope, aspect, Slope.compassSide(aspect), tpi, RidgeRule.isRidge(tpi), time)
        }
        return ExposureResult(out, lengthM, params, model.mode)
    }

    /**
     * The ground at ([lat], [lon]): slope (degrees), aspect (degrees from north, NaN on flat ground) and height, from
     * Horn's 3x3 stencil of [dem] samples [cellM] apart. NaN slope where the centre has no data.
     */
    fun ground(dem: ElevationSampler, lat: Double, lon: Double, cellM: Double): Triple<Double, Double, Double> {
        val v = DoubleArray(9)
        for (row in 0..2) for (col in 0..2) {
            val (la, lo) = RidgeRule.offset(lat, lon, (col - 1) * cellM, (1 - row) * cellM)
            v[row * 3 + col] = dem.elevation(la, lo)
        }
        val (dzdx, dzdy) = Slope.hornAt(v, cellM)
        if (dzdx.isNaN()) return Triple(Double.NaN, Double.NaN, v[4])
        return Triple(Slope.degrees(dzdx, dzdy), Slope.aspect(dzdx, dzdy), v[4])
    }
}

/**
 * T195: the words of a route's terrain (pure; `ExposureAlongTest`; ADR 0031, the owner adjusts them). The survey of
 * 2026-10-31: distance on the card, time in the JSON and on the sheet; the compass sides present, never a fixed
 * pair; the ridge figure on the sheet, off the card; words: slope, ground, facing, over, along; never avalanche,
 * hazard, danger, safe, risk, nor exposure as a rating.
 */
object ExposureText {
    /** Under the sheet: what the figures measure (the survey's help text, the ridge rule's sentence after it). */
    val HELP: String =
        "Steepness of the ground itself around the route, from the pack's elevation model, not the grade of the trail. " +
            "Model resolution limits accuracy; slopes near 30 and 35 degrees may differ on the ground. " +
            "This describes terrain; it is not a rating of safety or conditions. " +
            "Along ridges counts ground more than ${RidgeRule.THRESHOLD_M.toInt()} m above the average of the ground ${RidgeRule.RING_M.toInt()} m around it."

    /**
     * The route card's line: "1.2 km on slopes over 30 degrees, facing north and east; 0.4 km over 35 degrees" (the
     * second clause only with ground over 35), "No slopes over 30 degrees along the route", or null without data.
     */
    fun cardLine(r: ExposureResult, units: ContourUnits): String? {
        if (!r.hasData) return null
        if (r.over30.distanceM <= 0) return "No slopes over 30 degrees along the route"
        val sb = StringBuilder()
        sb.append(UnitFormat.distance(r.over30.distanceM, units)).append(" on slopes over 30 degrees, facing ").append(sideWords(r.shownSides))
        if (r.verySteep.distanceM > 0) sb.append("; ").append(UnitFormat.distance(r.verySteep.distanceM, units)).append(" over 35 degrees")
        return sb.toString()
    }

    /**
     * The compass sides in words, clockwise: one side "north", two "north and east", three or more in a row "northwest
     * to east" (adjacent sides around the circle), all eight "every direction"; the groups clockwise from north, joined
     * by commas and "and".
     */
    fun sideWords(sides: List<Int>): String {
        val set = sides.filter { it in 0..7 }.toSortedSet()
        if (set.isEmpty()) return "no one direction"
        if (set.size == 8) return "every direction"
        // Start just after a gap, so a run across north (northwest, north, northeast) stays one group.
        val start = (0 until 8).first { it in set && ((it + 7) % 8) !in set }
        val groups = ArrayList<List<Int>>()
        var cur = ArrayList<Int>()
        for (k in 0 until 8) {
            val s = (start + k) % 8
            if (s in set) cur += s else if (cur.isNotEmpty()) { groups += cur; cur = ArrayList() }
        }
        if (cur.isNotEmpty()) groups += cur
        // Clockwise from north: the group holding north first, then by their first side.
        groups.sortBy { g -> if (0 in g) -1 else g.first() }
        val items = groups.flatMap { g ->
            if (g.size >= 3) listOf("${Slope.COMPASS_SIDES[g.first()]} to ${Slope.COMPASS_SIDES[g.last()]}") else g.map { Slope.COMPASS_SIDES[it] }
        }
        return when (items.size) {
            1 -> items[0]
            else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
        }
    }

    /** The sheet's time line: "Time on slopes over 30 degrees: 25 min; over 35 degrees: 8 min" (null with none over 30). */
    fun timeLine(r: ExposureResult): String? {
        if (r.over30.distanceM <= 0) return null
        val s = "Time on slopes over 30 degrees: ${minutes(r.over30.timeS)}"
        return if (r.verySteep.distanceM > 0) "$s; over 35 degrees: ${minutes(r.verySteep.timeS)}" else s
    }

    /** The sheet's ridge line: "Along ridges: 0.8 km, 20 min" or "Along ridges: none" (null without data). */
    fun ridgeLine(r: ExposureResult, units: ContourUnits): String? {
        if (!r.hasData) return null
        if (r.ridge.distanceM <= 0) return "Along ridges: none"
        return "Along ridges: ${UnitFormat.distance(r.ridge.distanceM, units)}, ${minutes(r.ridge.timeS)}"
    }

    /**
     * One stretch on the sheet: "At 1.42 km: 350 m over 30 degrees (up to 38), facing north, 9 min" or "At 2.10 km:
     * 300 m along a ridge, 6 min".
     */
    fun row(s: ExposureStretch, units: ContourUnits): String {
        val at = "At ${UnitFormat.distance(s.fromM, units)}: ${UnitFormat.distance(s.distanceM, units)}"
        return when (s.kind) {
            StretchKind.SLOPE -> {
                val max = if (s.maxSlopeDeg.isNaN()) "" else " (up to ${s.maxSlopeDeg.roundToLong()})"
                val facing = if (s.sides.isEmpty()) "" else ", facing ${sideWords(s.sides)}"
                "$at over 30 degrees$max$facing, ${minutes(s.timeS)}"
            }
            StretchKind.RIDGE -> "$at along a ridge, ${minutes(s.timeS)}"
        }
    }

    /** "9 min", "1 h 05 min": a time to the minute. */
    fun minutes(s: Double): String {
        val m = (s / 60).roundToLong()
        return if (m < 60) "$m min" else "${m / 60} h ${(m % 60).toString().padStart(2, '0')} min"
    }
}

private fun r1(v: Double) = round(v * 10) / 10
private fun r6(v: Double) = round(v * 1e6) / 1e6
