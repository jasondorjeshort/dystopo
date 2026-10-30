package io.github.jasondorjeshort.dystopo.dem

import io.github.jasondorjeshort.dystopo.render.Slope
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan

/**
 * T165: how likely a derived channel is to carry water. **The project's own classes**: no published source gives
 * class boundaries by catchment (the research of 2026-10-30), so these come from the catchment area and the
 * mean slope of the catchment only, with no climate or season; a steep catchment moves both boundaries down
 * (wet mountain catchments run from smaller areas). [key] is the value written to a layer.
 */
enum class StreamClass(val key: String) {
    /** Under about 1 km²: a gully or swale that runs only after rain or snowmelt, if at all. */
    LIKELY_DRY("likely_dry"),

    /** About 1 to 10 km²: runs part of the year. */
    SEASONAL("seasonal"),

    /** Over about 10 km² (HydroRIVERS' cut-off, and where OSM maps most streams): likely runs all year. */
    LIKELY_RUNNING("likely_running"),
}

/**
 * Settings of [Drainage.extract]. The defaults are the research report's starting values tuned on `rainier`
 * against the mapped waterways (T165's report); every number here is a choice, not a standard.
 */
data class DrainageParams(
    /** Channel start: contributing area at [referenceSlopeDeg], km². About 0.1 on a 10 m grid, 0.25-0.5 on 30 m. */
    val thresholdKm2: Double = 0.1,
    /**
     * Montgomery-Dietrich gate (1988, 1992: channel heads where A·S² passes a constant): the start threshold
     * scales with (tan [referenceSlopeDeg] / tan S)² for the local slope S, clamped to [gateMin]..[gateMax], so a
     * steep hollow starts a channel earlier and gentle ground later.
     */
    val referenceSlopeDeg: Double = 15.0,
    val gateMin: Double = 0.5,
    val gateMax: Double = 4.0,
    /** Channel cells on ground flatter than this (lake surfaces, meadows, filled pits) are not drawn. */
    val flatDeg: Double = 1.0,
    /** A headwater piece (nothing drawn upstream of it) shorter than this many cells is dropped as noise. */
    val minCells: Int = 5,
    /** Class boundaries in km² for a gentle catchment ([StreamClass]). */
    val dryBelowKm2: Double = 1.0,
    val runningFromKm2: Double = 10.0,
    /** Catchments steeper on average than [steepFromDeg] have both boundaries scaled down, to [steepFactor] at [steepFullDeg]. */
    val steepFromDeg: Double = 15.0,
    val steepFullDeg: Double = 35.0,
    val steepFactor: Double = 0.5,
) {
    init {
        require(thresholdKm2 > 0) { "thresholdKm2 must be positive" }
        require(gateMin > 0 && gateMax >= gateMin) { "gate bounds must be positive and ordered" }
        require(dryBelowKm2 <= runningFromKm2) { "class boundaries out of order" }
        require(steepFullDeg > steepFromDeg && steepFactor > 0) { "steep scaling out of range" }
    }
}

/**
 * One derived channel between nodes (a channel head, a confluence, a class change or a break where drawing
 * stops). [points] holds interleaved x,y pairs in grid coordinates (cell (i, j) at x=i, y=j), upstream first;
 * the last point is shared with the line it flows into. [catchmentKm2] is the contributing area at the line's
 * downstream end (its own last cell); [catchmentComplete] is false when the upslope area may reach past the
 * grid, so the area is a lower bound. [cells] counts the line's own cells (the shared end point not included).
 */
data class DrainageLine(
    val points: DoubleArray,
    val catchmentKm2: Double,
    val streamClass: StreamClass,
    val catchmentComplete: Boolean,
    val cells: Int,
) {
    val pointCount: Int get() = points.size / 2

    override fun equals(other: Any?): Boolean = other is DrainageLine && points.contentEquals(other.points) &&
        catchmentKm2 == other.catchmentKm2 && streamClass == other.streamClass &&
        catchmentComplete == other.catchmentComplete && cells == other.cells

    override fun hashCode(): Int = ((points.contentHashCode() * 31 + catchmentKm2.hashCode()) * 31 + streamClass.hashCode()) * 31 + cells
}

/** Counters of one [Drainage.extract] run. */
data class DrainageStats(
    /** Cells where the gated threshold is first passed. */
    val sourceCells: Int,
    /** Cells downstream of a source, before the suppressions. */
    val channelCells: Int,
    /** Channel cells not drawn because the ground is flatter than [DrainageParams.flatDeg]. */
    val flatSuppressed: Int,
    /** Channel cells not drawn because the mask (water polygons) covers them. */
    val maskSuppressed: Int,
    /** Headwater pieces shorter than [DrainageParams.minCells] dropped. */
    val droppedShort: Int,
)

class DrainageResult(val lines: List<DrainageLine>, val stats: DrainageStats)

/** T165: stream lines from a [FlowGrid]: channel heads, the lines down to the outlets, their catchments and classes. */
object Drainage {
    private const val RAD = PI / 180.0

    /** [FlowGrid.build], the slope and [extract] in one call. */
    fun extract(
        grid: ElevationGrid,
        params: DrainageParams = DrainageParams(),
        suppress: BooleanArray? = null,
        rowCellAreaM2: DoubleArray? = null,
    ): DrainageResult = extract(FlowGrid.build(grid, rowCellAreaM2), Slope.compute(grid), params, suppress)

    /**
     * Lines over [flow]. [slopeDeg] is the local slope of the raw grid ([Slope.compute]), used for the
     * channel-start gate, the flat suppression and the class; [suppress] marks cells not to draw (water polygons).
     * The network keeps running under a suppressed cell, so a stream leaving a lake carries the lake's catchment.
     */
    fun extract(flow: FlowGrid, slopeDeg: FloatArray, params: DrainageParams = DrainageParams(), suppress: BooleanArray? = null): DrainageResult {
        val n = flow.size
        val w = flow.width
        require(slopeDeg.size == n) { "slope grid must be ${flow.width}x${flow.height}" }
        require(suppress == null || suppress.size == n) { "mask must be ${flow.width}x${flow.height}" }
        val area = flow.areaM2
        val receiver = flow.receiver
        val thresholdM2 = params.thresholdKm2 * 1e6
        val refTan = tan(params.referenceSlopeDeg * RAD)
        val dryM2 = params.dryBelowKm2 * 1e6
        val runningM2 = params.runningFromKm2 * 1e6

        // One downstream pass: own area times slope summed for the catchment's mean slope, the channel flag
        // (downstream of any source) and the class, never lower than any channel above it.
        val slopeArea = FloatArray(n)
        val channel = BooleanArray(n)
        val cls = ByteArray(n)
        var sources = 0
        for (c in flow.order) {
            val s = slopeDeg[c].let { if (it.isNaN()) 0f else it }
            val r = receiver[c]
            slopeArea[c] += s * flow.cellAreaM2(c)
            if (!channel[c]) {
                val t = tan(s * RAD)
                val gate = if (t <= 0.0) params.gateMax else ((refTan / t) * (refTan / t)).coerceIn(params.gateMin, params.gateMax)
                if (area[c] >= thresholdM2 * gate) {
                    channel[c] = true
                    sources++
                }
            }
            if (channel[c]) {
                val mean = slopeArea[c] / area[c]
                val own = classOf(area[c].toDouble(), mean.toDouble(), dryM2, runningM2, params)
                if (own > cls[c]) cls[c] = own
            }
            if (r < 0) continue
            slopeArea[r] += slopeArea[c]
            if (channel[c]) {
                channel[r] = true
                if (cls[c] > cls[r]) cls[r] = cls[c]
            }
        }

        // What is drawn, and how many drawn cells drain into each.
        val drawn = BooleanArray(n)
        var channelCells = 0
        var flat = 0
        var masked = 0
        for (c in 0 until n) {
            if (!channel[c]) continue
            channelCells++
            when {
                suppress != null && suppress[c] -> masked++
                slopeDeg[c] < params.flatDeg -> flat++
                else -> drawn[c] = true
            }
        }
        val inflow = ByteArray(n)
        for (c in 0 until n) {
            if (!drawn[c]) continue
            val r = receiver[c]
            if (r >= 0 && drawn[r] && inflow[r] < 8) inflow[r]++
        }

        // Trace from every node (no single drawn inflow); a class change mid-way starts a new line there.
        val lines = ArrayList<DrainageLine>()
        var dropped = 0
        val stack = ArrayList<Int>()
        val pts = ArrayList<Int>()
        for (start in 0 until n) {
            if (!drawn[start] || inflow[start].toInt() == 1) continue
            stack.add(start)
            while (stack.isNotEmpty()) {
                val first = stack.removeAt(stack.size - 1)
                pts.clear()
                pts.add(first)
                var cur = first
                var own = 1
                while (true) {
                    val next = receiver[cur]
                    if (next < 0 || !drawn[next]) break
                    pts.add(next)
                    if (inflow[next].toInt() != 1) break // a confluence: it starts its own line
                    if (cls[next] != cls[cur]) {
                        stack.add(next)
                        break
                    }
                    own++
                    cur = next
                }
                if (inflow[first].toInt() == 0 && own < params.minCells) {
                    dropped++
                    continue
                }
                val points = DoubleArray(pts.size * 2)
                for ((k, c) in pts.withIndex()) {
                    points[2 * k] = (c % w).toDouble()
                    points[2 * k + 1] = (c / w).toDouble()
                }
                lines += DrainageLine(
                    points = points,
                    catchmentKm2 = area[cur] / 1e6,
                    streamClass = StreamClass.entries[cls[first].toInt()],
                    catchmentComplete = !flow.open[cur],
                    cells = own,
                )
            }
        }
        return DrainageResult(lines, DrainageStats(sources, channelCells, flat, masked, dropped))
    }

    private fun classOf(areaM2: Double, meanSlopeDeg: Double, dryM2: Double, runningM2: Double, p: DrainageParams): Byte {
        val t = ((meanSlopeDeg - p.steepFromDeg) / (p.steepFullDeg - p.steepFromDeg)).coerceIn(0.0, 1.0)
        val f = 1.0 - t * (1.0 - p.steepFactor)
        return when {
            areaM2 >= runningM2 * f -> 2
            areaM2 >= dryM2 * f -> 1
            else -> 0
        }
    }

    /** Length of [line] in cells (diagonal steps count sqrt 2). */
    fun lengthCells(line: DrainageLine): Double {
        var len = 0.0
        val p = line.points
        for (k in 1 until line.pointCount) {
            val dx = abs(p[2 * k] - p[2 * k - 2])
            val dy = abs(p[2 * k + 1] - p[2 * k - 1])
            len += if (dx != 0.0 && dy != 0.0) 1.4142135623730951 else dx + dy
        }
        return len
    }
}
