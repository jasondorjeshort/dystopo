package io.github.jasondorjeshort.dystopo.dem

import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.mvt.Geometry
import io.github.jasondorjeshort.dystopo.mvt.MvtDecoder
import io.github.jasondorjeshort.dystopo.nav.RejoinHint
import io.github.jasondorjeshort.dystopo.search.LayerFilter
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.TimeSource

/**
 * One raster of a viewshed (T182): the cells of [window], each [Viewshed.NONE] (no ray reached it: outside the range),
 * [Viewshed.NO_DATA] (in range, but the terrain had no elevation there: where the data stopped), [Viewshed.HIDDEN] or
 * [Viewshed.VISIBLE]. ([cx], [cy]) is the observer in fractional cells of [window]; [cellM] the cell size there.
 *
 * The levels tile the disc exactly ([owns]): a level draws the cells whose centres lie at least [fromM] out (its finer
 * neighbour's band) and, but for the last, whose centre falls in a cell of the next coarser level ([outer]) that that
 * level leaves to it (a centre inside [fromM] of the coarser one); the last draws out to [toM], the range.
 */
class ViewshedLevel(
    val window: MercatorWindow,
    val fromM: Double,
    val toM: Double,
    val cellM: Double,
    val cx: Double,
    val cy: Double,
    val cells: ByteArray,
    /** The next coarser level, or null for the last. */
    val outer: ViewshedLevel?,
) {
    /** Distance (m) from the observer to the centre of cell ([i], [j]). */
    fun distanceM(i: Int, j: Int): Double {
        val dx = i + 0.5 - cx
        val dy = j + 0.5 - cy
        return sqrt(dx * dx + dy * dy) * cellM
    }

    private val outerScale: Double = outer?.let { o ->
        val shift = o.window.zoom - window.zoom
        if (shift >= 0) (1L shl shift).toDouble() else 1.0 / (1L shl -shift)
    } ?: 1.0

    /** True when cell ([i], [j]) is this level's to draw (see the class). */
    fun owns(i: Int, j: Int): Boolean {
        val d = distanceM(i, j)
        if (d < fromM) return false
        val o = outer ?: return d <= toM
        val oi = floor((window.x0 + i + 0.5) * outerScale - o.window.x0).toInt()
        val oj = floor((window.y0 + j + 0.5) * outerScale - o.window.y0).toInt()
        return o.distanceM(oi, oj) < o.fromM
    }

    /** Cells in [state] that this level draws ([owns]). */
    fun count(state: Byte): Int {
        var n = 0
        for (j in 0 until window.height) for (i in 0 until window.width) if (cells[j * window.width + i] == state && owns(i, j)) n++
        return n
    }
}

/**
 * What a viewshed from ([lat], [lon]) out to [rangeM] reads and writes (T182): per band of distance a raster level
 * ([levels]: the window, the band, how far the level's samples mark) and the terrain windows ([terrainWindows]) with
 * their bands in reference pixels, the same windows: the fine DEM near, coarser past [Viewshed.NEAR_M] and
 * [Viewshed.MID_M]. [rays] is the number of bearings, enough for neighbouring rays to be under a cell apart at each
 * level's outer edge.
 */
class ViewshedPlan(
    val lat: Double,
    val lon: Double,
    val rangeM: Double,
    val levels: List<Level>,
    val tileSize: Int,
) {
    /** One band: the raster [window], drawn [fromM] to [toM], its samples marking it out to [markToM]. */
    class Level(val window: MercatorWindow, val fromM: Double, val toM: Double, val markToM: Double, val cellM: Double)

    /** The terrain's windows, finest first, each with its band in pixels of the first (the [Terrain]'s reference zoom). */
    val terrainWindows: List<Pair<MercatorWindow, Double>> = levels.mapIndexed { i, l ->
        val refMpp = WebMercator.metersPerPixel(lat, levels[0].window.zoom, tileSize)
        l.window to (if (i == levels.size - 1) Double.POSITIVE_INFINITY else l.markToM / refMpp)
    }

    val rays: Int = levels.maxOf { l -> ceil(2 * PI * l.markToM / (l.cellM * Viewshed.RAY_SPACING)).toInt() }.coerceAtLeast(360)

    /** Cells read from the DEM over all windows. */
    val terrainCells: Long get() = levels.sumOf { it.window.width.toLong() * it.window.height }
}

/**
 * The result of [Viewshed.compute]: the [levels] (finest first), the [horizon] the rays met (one bin per ray, the
 * T181 [HorizonProfile]: its `reachedM` says how far the data went along each bearing), the observer's ground and eye,
 * and counters. Areas count each level's own cells (its band), in square kilometres.
 */
class ViewshedResult(
    val lat: Double,
    val lon: Double,
    val groundM: Double,
    val eyeM: Double,
    val rangeM: Double,
    val levels: List<ViewshedLevel>,
    val horizon: HorizonProfile,
    val samples: Long,
    val ms: Double,
) {
    val rays: Int get() = horizon.size

    private fun km2(state: Byte): Double = levels.sumOf { it.count(state) * it.cellM * it.cellM } / 1e6

    val visibleKm2: Double by lazy { km2(Viewshed.VISIBLE) }
    val hiddenKm2: Double by lazy { km2(Viewshed.HIDDEN) }
    val noDataKm2: Double by lazy { km2(Viewshed.NO_DATA) }

    /** Bearings along which the data stopped short of the range (by more than a coarse cell). */
    val bearingsShortOfData: Int by lazy {
        val slack = levels.last().cellM * 2
        horizon.reachedM.count { it < rangeM - slack }
    }

    /** The state of the ground at ([lat], [lon]): the finest level whose band holds it; [Viewshed.NONE] outside them all. */
    fun stateAt(lat: Double, lon: Double): Byte {
        for (l in levels) {
            val w = l.window
            val i = floor(WebMercator.lonToX(lon, w.zoom) * w.tileSize - w.x0).toInt()
            val j = floor(WebMercator.latToY(lat, w.zoom) * w.tileSize - w.y0).toInt()
            if (i < 0 || j < 0 || i >= w.width || j >= w.height) continue
            if (!l.owns(i, j)) continue
            return l.cells[j * w.width + i]
        }
        return Viewshed.NONE
    }

    /** The report both devtool and the phone print. */
    fun json(): Map<String, Any?> = linkedMapOf(
        "lat" to r6(lat), "lon" to r6(lon), "groundM" to r1(groundM), "eyeM" to eyeM, "rangeM" to rangeM.roundToLong(),
        "visibleKm2" to r1(visibleKm2), "hiddenKm2" to r1(hiddenKm2), "noDataKm2" to r1(noDataKm2),
        "visibleFraction" to r4(visibleKm2 / maxOf(1e-9, visibleKm2 + hiddenKm2)),
        "rays" to rays, "samples" to samples, "sweepMs" to r1(ms),
        "dataStopped" to linkedMapOf(
            "bearingsShort" to bearingsShortOfData, "nearestM" to horizon.reachedM.min().roundToLong(),
            "farthestM" to horizon.reachedM.max().roundToLong(),
        ),
        "levels" to levels.map { l ->
            linkedMapOf(
                "zoom" to l.window.zoom, "x0" to l.window.x0, "y0" to l.window.y0, "width" to l.window.width, "height" to l.window.height,
                "cellM" to r1(l.cellM), "fromM" to l.fromM.roundToLong(), "toM" to l.toM.roundToLong(),
                "visible" to l.count(Viewshed.VISIBLE), "hidden" to l.count(Viewshed.HIDDEN), "noData" to l.count(Viewshed.NO_DATA),
            )
        },
        "horizon" to linkedMapOf(
            "highestDeg" to r2(horizon.angleDeg[horizon.highest]), "highestBearingDeg" to r1(horizon.bearingDeg(horizon.highest)),
            "highestDistanceM" to horizon.distanceM[horizon.highest].roundToLong(),
        ),
    )
}

/** A named summit from the pack's `mountain_peak` layer (T182): [kind] its class, [eleM] its tagged elevation or null. */
data class Peak(val name: String, val kind: String, val lat: Double, val lon: Double, val eleM: Double?)

/**
 * A [Peak] seen from the observer (T182): [distanceM] (great circle), [bearingDeg] true (clockwise from true north) with
 * its [compass] letters, [eleM] the elevation used (the tag, else the DEM), [angleDeg] its apparent elevation angle
 * (curvature and refraction), [visible] when the line of sight to its top is clear (to [Viewshed.PEAK_TOLERANCE_M] or
 * half a terrain cell there), [clearanceDeg] by how much (negative: hidden by that much), [skyline] when it stands
 * on the horizon line (nothing farther out along its bearing rises above it).
 */
data class PeakView(
    val peak: Peak,
    val distanceM: Double,
    val bearingDeg: Double,
    val compass: String,
    val eleM: Double,
    val eleFromDem: Boolean,
    val angleDeg: Double,
    val visible: Boolean,
    val clearanceDeg: Double,
    val skyline: Boolean,
) {
    fun json(): Map<String, Any?> = linkedMapOf(
        "name" to peak.name, "kind" to peak.kind, "lat" to r6(peak.lat), "lon" to r6(peak.lon),
        "eleM" to eleM.roundToLong(), "eleFrom" to if (eleFromDem) "dem" else "tag",
        "distanceM" to distanceM.roundToLong(), "bearingDeg" to r1(bearingDeg), "compass" to compass,
        "angleDeg" to r2(angleDeg), "visible" to visible, "clearanceDeg" to r2(clearanceDeg), "skyline" to skyline,
    )
}

/** How a peak list is sorted (T182; the survey: distance first, what a walker asks, then elevation or bearing). */
enum class PeakSort(val key: String, val label: String) {
    DISTANCE("distance", "Distance"),
    ELEVATION("elevation", "Elevation"),
    BEARING("bearing", "Bearing");

    fun sort(peaks: List<PeakView>): List<PeakView> = when (this) {
        DISTANCE -> peaks.sortedBy { it.distanceM }
        ELEVATION -> peaks.sortedByDescending { it.eleM }
        BEARING -> peaks.sortedBy { it.bearingDeg }
    }

    companion object {
        fun of(key: String?): PeakSort = entries.firstOrNull { it.key == key } ?: DISTANCE
    }
}

/**
 * What can I see from here (T182, stages 1 and 2 of the viewshed idea): the ground visible from a point at eye height
 * out to a range, and the named peaks in view.
 *
 * **The sweep.** Rays leave the observer every `360 / rays` degrees ([ViewshedPlan.rays]: neighbours under
 * [RAY_SPACING] of a cell apart at each level's outer edge) and march outward in steps of [STEP_CELLS] of the band's
 * cell over the T181 [Terrain] (the fine DEM out to [NEAR_M], one about four times coarser out to [MID_M], one
 * sixteen times coarser past it), each keeping the highest apparent tangent so far with Earth's curvature and
 * refraction ([Horizon.REFRACTION_K]): a sample is visible when its tangent reaches that maximum. Each sample marks the
 * raster cell of every level whose band holds it, a visible mark winning over a hidden one over no data (a cell is
 * visible when any ray sees ground in it). Where the terrain has no elevation the cell is marked [NO_DATA]: the overlay
 * says where the data stopped, and the horizon's `reachedM` how far it went along each bearing.
 *
 * **The levels.** One raster per band ([ViewshedLevel]): the near one at about 15 m cells, then 60 m, then 240 m, each
 * about a thousand cells across, drawn as translucent images that tile the disc exactly ([ViewshedLevel.owns]): the
 * finer level marks and draws [OVERLAP_CELLS] of the coarser cell past its band, where the coarser level's cells whose
 * centres lie inside its band are left to it.
 *
 * **The peaks.** [readPeaks] takes the named peaks and volcanoes of the pack's `mountain_peak` layer
 * (`tilebuild/PROFILE.md`) in range; [peaks] tests each with its own ray ([RayMarch], fine steps, the last
 * [PEAK_MARGIN] of the way left out so a summit's own shoulder does not hide it) and the horizon of the sweep.
 *
 * The phone runs [compute] off the UI thread (one [RayMarch] per call); devtool's `viewshed` prints [ViewshedResult.json].
 */
object Viewshed {
    const val NONE: Byte = 0
    const val NO_DATA: Byte = 1
    const val HIDDEN: Byte = 2
    const val VISIBLE: Byte = 3

    /** Eye height of a standing person (the survey: ArcGIS 1.8 m, one value is enough). */
    const val EYE_HEIGHT_M = Horizon.EYE_HEIGHT_M

    /** The range choices, km (the `viewRange` setting; the survey: the apps split, the phone's cost differs). */
    val RANGES_KM = listOf(10, 30, 100)

    /** The default range, km. */
    const val DEFAULT_RANGE_KM = 30

    /** The longest range the plan accepts, metres. */
    const val MAX_RANGE_M = 100_000.0

    /** The fine band's outer edge, metres: the fine DEM is read out to here. */
    const val NEAR_M = 5_000.0

    /** The middle band's outer edge, metres. */
    const val MID_M = 30_000.0

    /** Target cell sizes of the three bands, metres (each band's zoom is the nearest at the observer's latitude). */
    val BAND_CELL_M = doubleArrayOf(15.0, 60.0, 240.0)

    /**
     * Neighbouring rays at a level's outer edge are at most this share of its cell apart. With [STEP_CELLS] every cell
     * is sampled: the nearest ray passes within 0.3 of a cell of its centre, where even a diagonal chord through it is
     * 0.81 of a cell, longer than a step (0.9 left a few cells at the band's edge unmarked).
     */
    const val RAY_SPACING = 0.6

    /** A ray's step, in cells of the band it is in. */
    const val STEP_CELLS = 0.75

    /** The finer level marks this many of the coarser level's cells past its band (the coarser level's cells it draws). */
    const val OVERLAP_CELLS = 1.5

    /** Overlay: visible ground in a translucent green (the survey: green-ish at about 35 percent), hidden ground clear. */
    const val OVERLAY_RGB = 0x12A150
    const val OVERLAY_OPACITY = 0.35

    /** Overlay: where the data stopped, a grey hatch (one line in [HATCH_PERIOD] cells, diagonal). */
    const val NO_DATA_RGB = 0x5A5A5A
    const val NO_DATA_OPACITY = 0.55
    const val HATCH_PERIOD = 6

    /** A peak's top counts as seen when the line of sight passes within this of it (OSM placement, the DEM's shaved tops). */
    const val PEAK_TOLERANCE_M = 10.0

    /** The share of the way to a peak left out of its ray (its own shoulder), at least two terrain cells. */
    const val PEAK_MARGIN = 0.01

    /** `mountain_peak` classes listed as peaks. */
    val PEAK_CLASSES = setOf("peak", "volcano", "hill")

    private const val DEG = PI / 180

    /** The zoom whose cells are nearest [cellM] metres at [lat]. */
    fun zoomFor(lat: Double, cellM: Double, tileSize: Int = 256, maxZoom: Int = 15): Int {
        val m0 = WebMercator.metersPerPixel(lat, 0, tileSize)
        return (ln(m0 / cellM) / ln(2.0)).roundToInt().coerceIn(0, maxZoom)
    }

    /** The plan of a viewshed from ([lat], [lon]) out to [rangeM] (at most [MAX_RANGE_M]). */
    fun plan(lat: Double, lon: Double, rangeM: Double, tileSize: Int = 256): ViewshedPlan {
        require(rangeM > 0 && rangeM <= MAX_RANGE_M) { "rangeM must be in (0, $MAX_RANGE_M]" }
        val edges = doubleArrayOf(NEAR_M, MID_M, Double.POSITIVE_INFINITY)
        val bands = ArrayList<Pair<Double, Double>>()
        var from = 0.0
        for (e in edges) {
            if (from >= rangeM) break
            val to = minOf(rangeM, e)
            bands += from to to
            from = to
        }
        val zooms = bands.indices.map { zoomFor(lat, BAND_CELL_M[it], tileSize) }
        val gx0 = WebMercator.lonToX(lon, 0)
        val gy0 = WebMercator.latToY(lat, 0)
        val levels = bands.mapIndexed { i, (f, t) ->
            val z = zooms[i]
            val mpp = WebMercator.metersPerPixel(lat, z, tileSize)
            val next = if (i + 1 < bands.size) WebMercator.metersPerPixel(lat, zooms[i + 1], tileSize) else 0.0
            val markTo = t + OVERLAP_CELLS * next
            val r = ceil(markTo / mpp).toInt() + 2
            val scale = (1L shl z).toDouble() * tileSize
            val cx = floor(gx0 * scale).toInt()
            val cy = floor(gy0 * scale).toInt()
            ViewshedPlan.Level(MercatorWindow(z, cx - r, cy - r, 2 * r + 1, 2 * r + 1, tileSize), f, t, markTo, mpp)
        }
        return ViewshedPlan(lat, lon, rangeM, levels, tileSize)
    }

    /** The terrain of [plan], each window read by [read] (`DemPyramid::window` on the phone, an elevation source in devtool). */
    fun terrain(plan: ViewshedPlan, read: (MercatorWindow) -> ElevationGrid): Terrain = Terrain.read(plan.terrainWindows, read)

    /**
     * The viewshed of [plan] over [terrain] (read from [ViewshedPlan.terrainWindows]) at [eyeM] above the ground.
     * Throws when the terrain has no elevation at the observer.
     */
    fun compute(terrain: Terrain, plan: ViewshedPlan, eyeM: Double = EYE_HEIGHT_M, k: Double = Horizon.REFRACTION_K, rays: Int = plan.rays): ViewshedResult {
        val started = TimeSource.Monotonic.markNow()
        val lat = plan.lat
        val lon = plan.lon
        val gx = terrain.gx(lon)
        val gy = terrain.gy(lat)
        val ground = terrain.sample(gx, gy)
        require(ground != Terrain.OUTSIDE && !ground.isNaN()) { "no elevation at $lat, $lon" }
        val eye = ground + eyeM
        val refMpp = WebMercator.metersPerPixel(lat, terrain.zoom, terrain.tileSize)
        val pxPerM = 1 / refMpp
        val curvature = (1 - k) / (2 * Horizon.EARTH_RADIUS_M)
        val n = rays.coerceAtLeast(8)
        val lv = plan.levels
        val count = lv.size
        val cells = Array(count) { ByteArray(lv[it].window.width * lv[it].window.height) }
        val scale = DoubleArray(count) {
            val shift = lv[it].window.zoom - terrain.zoom
            if (shift >= 0) (1L shl shift).toDouble() else 1.0 / (1L shl -shift)
        }
        val ox = DoubleArray(count) { lv[it].window.x0.toDouble() }
        val oy = DoubleArray(count) { lv[it].window.y0.toDouble() }
        val ww = IntArray(count) { lv[it].window.width }
        val wh = IntArray(count) { lv[it].window.height }
        val from = DoubleArray(count) { lv[it].fromM }
        val markTo = DoubleArray(count) { lv[it].markToM }
        val step = DoubleArray(count) { lv[it].cellM * STEP_CELLS }
        val angle = DoubleArray(n)
        val dist = DoubleArray(n)
        val reached = DoubleArray(n)
        val range = plan.rangeM
        var samples = 0L
        // The observer's own cell: the first step can land outside it.
        run {
            val i = floor(gx * scale[0] - ox[0]).toInt()
            val j = floor(gy * scale[0] - oy[0]).toInt()
            if (i in 0 until ww[0] && j in 0 until wh[0]) cells[0][j * ww[0] + i] = VISIBLE
        }
        for (r in 0 until n) {
            val b = r * 2 * PI / n
            val dx = sin(b) * pxPerM
            val dy = -cos(b) * pxPerM
            var best = Double.NEGATIVE_INFINITY
            var bestS = 0.0
            var reach = 0.0
            var band = 0
            var next = step[0]
            while (true) {
                val s = minOf(next, range) // the last sample at the range itself, so the cells at its edge are reached
                while (band < count - 1 && s > markTo[band]) band++
                val px = gx + dx * s
                val py = gy + dy * s
                val v = terrain.sample(px, py, s * pxPerM)
                val state: Byte
                if (v == Terrain.OUTSIDE || v.isNaN()) {
                    state = NO_DATA
                } else {
                    samples++
                    reach = s
                    val t = (v - curvature * s * s - eye) / s
                    state = if (t >= best) VISIBLE else HIDDEN
                    if (t > best) {
                        best = t
                        bestS = s
                    }
                }
                for (l in 0 until count) {
                    if (s < from[l] || s > markTo[l]) continue
                    val i = floor(px * scale[l] - ox[l]).toInt()
                    val j = floor(py * scale[l] - oy[l]).toInt()
                    if (i < 0 || j < 0 || i >= ww[l] || j >= wh[l]) continue
                    val k2 = j * ww[l] + i
                    if (cells[l][k2] < state) cells[l][k2] = state
                }
                if (s >= range) break
                next = s + step[band]
            }
            angle[r] = if (best == Double.NEGATIVE_INFINITY) 0.0 else atan(best) / DEG
            dist[r] = bestS
            reached[r] = reach
        }
        val empty = reached.count { it == 0.0 }
        val horizon = HorizonProfile(lat, lon, ground, eyeM, 360.0 / n, range, angle, dist, reached, samples, empty)
        var outer: ViewshedLevel? = null
        val levels = lv.indices.reversed().map { i ->
            val l = lv[i]
            val w = l.window
            val cx = WebMercator.lonToX(lon, w.zoom) * w.tileSize - w.x0
            val cy = WebMercator.latToY(lat, w.zoom) * w.tileSize - w.y0
            ViewshedLevel(w, l.fromM, l.toM, l.cellM, cx, cy, cells[i], outer).also { outer = it }
        }.reversed()
        val ms = started.elapsedNow().inWholeMicroseconds / 1000.0
        return ViewshedResult(lat, lon, ground, eyeM, range, levels, horizon, samples, ms)
    }

    /**
     * The overlay of one level as ARGB pixels (not premultiplied, row-major, the level's window): visible ground in
     * [rgb] at [opacity], hidden ground and cells outside the level's band clear, cells without data hatched grey.
     */
    fun overlayArgb(level: ViewshedLevel, rgb: Int = OVERLAY_RGB, opacity: Double = OVERLAY_OPACITY): IntArray {
        val w = level.window.width
        val h = level.window.height
        val visible = (((opacity * 255).roundToInt().coerceIn(0, 255)) shl 24) or (rgb and 0xFFFFFF)
        val hatch = (((NO_DATA_OPACITY * 255).roundToInt()) shl 24) or NO_DATA_RGB
        val out = IntArray(w * h)
        for (j in 0 until h) for (i in 0 until w) {
            val st = level.cells[j * w + i]
            if (st == NONE || st == HIDDEN) continue
            if (!level.owns(i, j)) continue
            out[j * w + i] = when (st) {
                VISIBLE -> visible
                else -> if (((level.window.x0 + i) + (level.window.y0 + j)).mod(HATCH_PERIOD) == 0) hatch else 0
            }
        }
        return out
    }

    /** The zoom [readPeaks] reads the `mountain_peak` layer at for [rangeM]: tiles a quarter of the range or wider, z9 to z12. */
    fun peakZoom(lat: Double, rangeM: Double): Int {
        val world = 2 * PI * Horizon.EARTH_RADIUS_M * cos(lat * DEG)
        return floor(ln(world / (rangeM / 4)) / ln(2.0)).toInt().coerceIn(9, 12)
    }

    /**
     * The named peaks (classes [PEAK_CLASSES]) of the `mountain_peak` layer within [rangeM] of ([lat], [lon]), read at
     * [zoom] from [tiles] (a vector pack's `tile(z, x, y)`, null where it has none). A point counts in its own tile
     * only (tiles carry a buffer); the same name within 200 m is one peak.
     */
    fun readPeaks(tiles: (Int, Int, Int) -> ByteArray?, lat: Double, lon: Double, rangeM: Double, zoom: Int = peakZoom(lat, rangeM)): List<Peak> {
        val n = 1 shl zoom
        val mpp = WebMercator.metersPerPixel(lat, zoom, 1)
        val cx = WebMercator.lonToX(lon, zoom)
        val cy = WebMercator.latToY(lat, zoom)
        val r = rangeM / mpp
        val out = ArrayList<Peak>()
        for (ty in floor(cy - r).toInt().coerceAtLeast(0)..floor(cy + r).toInt().coerceAtMost(n - 1)) {
            for (tx in floor(cx - r).toInt()..floor(cx + r).toInt()) {
                val bytes = tiles(zoom, tx.mod(n), ty) ?: continue
                val layer = runCatching { MvtDecoder.decode(LayerFilter.filter(bytes, setOf("mountain_peak"))).layer("mountain_peak") }.getOrNull() ?: continue
                for (f in layer.features) {
                    val kind = f.properties["class"]?.asText() ?: continue
                    if (kind !in PEAK_CLASSES) continue
                    val name = f.properties["name"]?.asText()?.takeIf { it.isNotBlank() } ?: continue
                    val pts = (f.geometry as? Geometry.Points)?.points ?: continue
                    for (p in pts) {
                        if (p.x < 0 || p.y < 0 || p.x >= layer.extent || p.y >= layer.extent) continue
                        val plon = WebMercator.xToLon(tx + p.x.toDouble() / layer.extent, zoom)
                        val plat = WebMercator.yToLat(ty + p.y.toDouble() / layer.extent, zoom)
                        if (TrackStats.distanceM(lat, lon, plat, plon) > rangeM) continue
                        if (out.any { it.name == name && TrackStats.distanceM(it.lat, it.lon, plat, plon) < 200 }) continue
                        out += Peak(name, kind, plat, plon, f.properties["ele"]?.asDouble())
                    }
                }
            }
        }
        return out
    }

    /**
     * Each of [peaks] as seen from [result]'s observer over [terrain] (the sweep's): its distance, true bearing and
     * angle, whether its top is in view (its own [RayMarch] in steps of half a percent of the distance, stopping
     * [PEAK_MARGIN] of the way, at least two terrain cells, short of it) and whether it stands on the sweep's horizon.
     * The elevation is the tag's unless the DEM there differs by more than 150 m (a wrong tag), then the DEM's.
     * Sorted by distance.
     */
    fun peaks(terrain: Terrain, result: ViewshedResult, peaks: List<Peak>, k: Double = Horizon.REFRACTION_K): List<PeakView> {
        val gx = terrain.gx(result.lon)
        val gy = terrain.gy(result.lat)
        val refMpp = WebMercator.metersPerPixel(result.lat, terrain.zoom, terrain.tileSize)
        val eye = result.groundM + result.eyeM
        val march = RayMarch(terrain, result.rangeM, PEAK_STEP_GROWTH, k)
        val out = ArrayList<PeakView>()
        for (p in peaks) {
            val d = TrackStats.distanceM(result.lat, result.lon, p.lat, p.lon)
            if (d < 1 || d > result.rangeM) continue
            val bearing = TrackStats.bearingDeg(result.lat, result.lon, p.lat, p.lon)
            val dem = terrain.sample(terrain.gx(p.lon), terrain.gy(p.lat), d / refMpp).takeIf { it != Terrain.OUTSIDE && !it.isNaN() }
            val tag = p.eleM
            val fromDem = tag == null || (dem != null && kotlin.math.abs(tag - dem) > 150)
            val ele = (if (fromDem) dem else tag) ?: continue
            val cellM = terrainCellAt(terrain, d, refMpp)
            val tolM = max(PEAK_TOLERANCE_M, cellM / 2)
            val drop = Horizon.dropM(d, k)
            val tp = (ele - drop - eye) / d
            val b = bearing * DEG
            val stopAt = d - max(d * PEAK_MARGIN, 2 * cellM)
            val tb = if (stopAt > refMpp) march.maxTangent(gx, gy, eye, sin(b), cos(b), 1 / refMpp, refMpp, rangeM = stopAt) else Double.NEGATIVE_INFINITY
            val angle = atan(tp) / DEG
            val clearance = if (tb == Double.NEGATIVE_INFINITY) 90.0 else angle - atan(tb) / DEG
            val visible = tp + tolM / d >= tb
            val skyline = visible && angle + atan(tolM / d) / DEG >= result.horizon.angleAt(bearing)
            out += PeakView(p, d, bearing, RejoinHint.compassWord(bearing), ele, fromDem, angle, visible, clearance, skyline)
        }
        return out.sortedBy { it.distanceM }
    }

    /** A peak ray's step grows by this share of its distance (finer than the horizon's 2 percent: a ray to one target). */
    const val PEAK_STEP_GROWTH = 0.005

    /** The cell size (m) of the terrain layer a sample [distanceM] out reads (the first whose band reaches it). */
    private fun terrainCellAt(terrain: Terrain, distanceM: Double, refMpp: Double): Double {
        val px = distanceM / refMpp
        val layer = terrain.layers.firstOrNull { px <= it.untilPx } ?: terrain.layers.last()
        return refMpp * (1L shl (terrain.zoom - layer.window.zoom).coerceAtLeast(0))
    }
}

private fun r1(v: Double) = kotlin.math.round(v * 10) / 10
private fun r2(v: Double) = kotlin.math.round(v * 100) / 100
private fun r4(v: Double) = kotlin.math.round(v * 10_000) / 10_000
private fun r6(v: Double) = kotlin.math.round(v * 1e6) / 1e6
