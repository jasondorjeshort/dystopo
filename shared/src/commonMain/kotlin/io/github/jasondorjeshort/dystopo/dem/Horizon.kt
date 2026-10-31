package io.github.jasondorjeshort.dystopo.dem

import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * One elevation grid placed on the Web Mercator world (T181): [grid] holds the cells of [window] (cell (i, j) centred
 * on global pixel `x0 + i + 0.5`, `y0 + j + 0.5` at the window's zoom). A [Terrain] uses the layer for samples up to
 * [untilPx] from the point a ray starts at, in pixels of the terrain's reference zoom (its first layer's): the finest
 * layer near, coarser ones farther out.
 */
class TerrainLayer(val window: MercatorWindow, val grid: ElevationGrid, val untilPx: Double = Double.POSITIVE_INFINITY) {
    init {
        require(grid.width == window.width && grid.height == window.height) {
            "grid ${grid.width}x${grid.height} does not match window ${window.width}x${window.height}"
        }
        require(untilPx > 0) { "untilPx must be positive" }
    }

    /** Highest elevation in the layer (NaN ignored); negative infinity when it has no data. */
    val maxElevation: Double = grid.max().toDouble()

    /**
     * True when the layer has no cell without data: only then may a ray skip across its blocks (where a cell has no
     * data a sample falls back to a coarser layer, which the block maxima do not bound).
     */
    internal val skippable: Boolean = grid.values.none { it.isNaN() }

    /**
     * Block maxima for skipping (built on first use): level `L` (index `L - 1`, up to [SKIP_LEVELS]) holds, per block of
     * `2^L` by `2^L` cells, the highest cell within one cell of the block (a bilinear sample in the block reads its
     * neighbours too).
     */
    internal val maxLevels: Array<FloatArray> by lazy {
        val w = grid.width
        val h = grid.height
        val v = grid.values
        var level = FloatArray(w * h) { k ->
            val x = k % w
            val y = k / w
            var m = Float.NEGATIVE_INFINITY
            for (j in maxOf(0, y - 1)..minOf(h - 1, y + 1)) for (i in maxOf(0, x - 1)..minOf(w - 1, x + 1)) {
                val e = v[j * w + i]
                if (e > m) m = e
            }
            m
        }
        var lw = w
        var lh = h
        val out = ArrayList<FloatArray>()
        for (l in 1..SKIP_LEVELS) {
            val nw = (lw + 1) / 2
            val nh = (lh + 1) / 2
            val prev = level
            val pw = lw
            val ph = lh
            level = FloatArray(nw * nh) { k ->
                val x = 2 * (k % nw)
                val y = 2 * (k / nw)
                var m = prev[y * pw + x]
                if (x + 1 < pw && prev[y * pw + x + 1] > m) m = prev[y * pw + x + 1]
                if (y + 1 < ph) {
                    if (prev[(y + 1) * pw + x] > m) m = prev[(y + 1) * pw + x]
                    if (x + 1 < pw && prev[(y + 1) * pw + x + 1] > m) m = prev[(y + 1) * pw + x + 1]
                }
                m
            }
            out += level
            lw = nw
            lh = nh
        }
        out.toTypedArray()
    }

    internal companion object {
        /** Block levels for skipping: up to 64 cells a side. */
        const val SKIP_LEVELS = 6
    }

    /**
     * Bilinear elevation at global pixel ([gx], [gy]) of zoom [refZoom]: [Terrain.OUTSIDE] when the point is not
     * between this layer's cell centres, NaN when one of the four cells has no data.
     */
    internal fun sample(gx: Double, gy: Double, refZoom: Int): Double {
        val shift = window.zoom - refZoom
        val scale = if (shift >= 0) (1L shl shift).toDouble() else 1.0 / (1L shl -shift)
        return at(gx * scale - window.x0 - 0.5, gy * scale - window.y0 - 0.5)
    }

    /** Bilinear elevation at fractional cell ([px], [py]) of [grid]; [Terrain.OUTSIDE] off the cell centres. */
    internal fun at(px: Double, py: Double): Double {
        val w = grid.width
        if (px < 0 || py < 0 || px > w - 1 || py > grid.height - 1) return Terrain.OUTSIDE
        val x0 = floor(px).toInt().coerceAtMost(w - 2).coerceAtLeast(0)
        val y0 = floor(py).toInt().coerceAtMost(grid.height - 2).coerceAtLeast(0)
        val fx = px - x0
        val fy = py - y0
        val v = grid.values
        val k = y0 * w + x0
        if (w == 1 || grid.height == 1) return v[k].toDouble()
        val a = v[k]
        val b = v[k + 1]
        val c = v[k + w]
        val d = v[k + w + 1]
        val top = a + (b - a) * fx
        val bottom = c + (d - c) * fx
        return top + (bottom - top) * fy // NaN in, NaN out
    }
}

/**
 * Terrain around a point or a window for marching rays over (T181): [layers] finest first, each used up to its
 * [TerrainLayer.untilPx] from the ray's start, then the next. Coordinates are global pixels at [zoom] (the first
 * layer's). A sample falls back to the next coarser layer where a finer one is outside or has no data there.
 * The same global cell always gives the same value whichever windows the layers were cut from, so two tiles
 * marching over the same ground agree cell for cell.
 */
class Terrain(val layers: List<TerrainLayer>) {
    init {
        require(layers.isNotEmpty()) { "a terrain needs at least one layer" }
        require(layers.all { it.window.tileSize == layers[0].window.tileSize }) { "layers must share a tile size" }
    }

    /** The reference zoom: global pixel coordinates of every call are at this zoom. */
    val zoom: Int get() = layers[0].window.zoom

    val tileSize: Int get() = layers[0].window.tileSize

    /** Highest elevation in any layer: no sample can be higher (the rays' early exit). */
    val maxElevation: Double = layers.maxOf { it.maxElevation }

    // Per layer, for the inner loop of every ray: the scale from reference pixels, the offset to cell coordinates, the band.
    private val count = layers.size
    private val layerArray = layers.toTypedArray()
    private val scale = DoubleArray(count) {
        val shift = layers[it].window.zoom - layers[0].window.zoom
        if (shift >= 0) (1L shl shift).toDouble() else 1.0 / (1L shl -shift)
    }
    private val offsetX = DoubleArray(count) { layers[it].window.x0 + 0.5 }
    private val offsetY = DoubleArray(count) { layers[it].window.y0 + 0.5 }
    private val until = DoubleArray(count) { layers[it].untilPx }

    /**
     * Elevation at global pixel ([gx], [gy]) for a sample [distancePx] (reference pixels) from the ray's start:
     * the finest layer whose band reaches that far and has data there; NaN when the layers that cover it have no
     * data, [OUTSIDE] when none covers it.
     */
    fun sample(gx: Double, gy: Double, distancePx: Double = 0.0): Double {
        var covered = false
        for (i in 0 until count) {
            if (distancePx > until[i]) continue
            val v = layerArray[i].at(gx * scale[i] - offsetX[i], gy * scale[i] - offsetY[i])
            if (v == OUTSIDE) continue
            covered = true
            if (!v.isNaN()) return v
        }
        return if (covered) Double.NaN else OUTSIDE
    }

    /**
     * Into [out], for skipping: the layer a sample at global pixel ([gx], [gy]) [distancePx] out would read, its block
     * at [level] there ([TerrainLayer.maxLevels]): the block's highest elevation (`out[0]`) and how far (reference
     * pixels) a ray along the unit pixel direction ([dx], [dy]) stays in that block, in the layer and in its band
     * (`out[1]`). False when that layer cannot be skipped (cells without data) or no layer covers the point.
     */
    internal fun block(gx: Double, gy: Double, distancePx: Double, dx: Double, dy: Double, level: Int, out: DoubleArray): Boolean {
        for (i in 0 until count) {
            if (distancePx > until[i]) continue
            val layer = layerArray[i]
            val w = layer.grid.width
            val h = layer.grid.height
            val px = gx * scale[i] - offsetX[i]
            val py = gy * scale[i] - offsetY[i]
            if (px < 0 || py < 0 || px > w - 1 || py > h - 1) continue
            if (!layer.skippable || w < 2 || h < 2) return false
            val size = 1 shl level
            val bx = floor(px).toInt().coerceIn(0, w - 2) shr level
            val by = floor(py).toInt().coerceIn(0, h - 2) shr level
            val lw = (w + size - 1) shr level
            out[0] = layer.maxLevels[level - 1][by * lw + bx].toDouble()
            // Layer pixels per reference pixel along the ray; the exit through the block's sides, the layer's last
            // cell centres and the band's end, whichever comes first.
            val ux = dx * scale[i]
            val uy = dy * scale[i]
            fun exit(p: Double, u: Double, lo: Double, hi: Double): Double = when {
                u > 1e-12 -> (hi - p) / u
                u < -1e-12 -> (lo - p) / u
                else -> Double.POSITIVE_INFINITY
            }
            val bx0 = (bx shl level).toDouble()
            val by0 = (by shl level).toDouble()
            out[1] = minOf(
                minOf(exit(px, ux, bx0, bx0 + size), exit(py, uy, by0, by0 + size)),
                minOf(exit(px, ux, 0.0, (w - 1).toDouble()), exit(py, uy, 0.0, (h - 1).toDouble())),
                until[i] - distancePx,
            )
            return true
        }
        return false
    }

    /** Global pixel x at [zoom] of longitude [lon]. */
    fun gx(lon: Double): Double = WebMercator.lonToX(lon, zoom) * tileSize

    /** Global pixel y at [zoom] of latitude [lat]. */
    fun gy(lat: Double): Double = WebMercator.latToY(lat, zoom) * tileSize

    /**
     * How far (reference pixels) a ray from ([gx], [gy]) along the unit pixel direction ([dx], [dy]) stays between some
     * layer's cell centres: where the data ends that way.
     */
    fun exitPx(gx: Double, gy: Double, dx: Double, dy: Double): Double {
        var best = 0.0
        for (layer in layers) {
            val w = layer.window
            val shift = w.zoom - zoom
            val scale = if (shift >= 0) (1L shl shift).toDouble() else 1.0 / (1L shl -shift)
            val x0 = (w.x0 + 0.5) / scale
            val x1 = (w.x0 + w.width - 0.5) / scale
            val y0 = (w.y0 + 0.5) / scale
            val y1 = (w.y0 + w.height - 0.5) / scale
            if (gx < x0 || gx > x1 || gy < y0 || gy > y1) continue
            val tx = if (dx > 1e-12) (x1 - gx) / dx else if (dx < -1e-12) (x0 - gx) / dx else Double.POSITIVE_INFINITY
            val ty = if (dy > 1e-12) (y1 - gy) / dy else if (dy < -1e-12) (y0 - gy) / dy else Double.POSITIVE_INFINITY
            best = maxOf(best, minOf(tx, ty))
        }
        return best
    }

    /** Ground elevation at a point (the finest layer); NaN without data. */
    fun elevationAt(lat: Double, lon: Double): Double = sample(gx(lon), gy(lat)).let { if (it == OUTSIDE) Double.NaN else it }

    companion object {
        /** [sample]'s answer for a point no layer covers. */
        const val OUTSIDE = Double.NEGATIVE_INFINITY

        /**
         * A terrain from the [windows] a plan asks for (each with its band, [Horizon.windowsAround] or
         * `CastShadow.windowsFor`), each read by [read]: `DemPyramid::window` on the phone, an elevation source in
         * devtool.
         */
        fun read(windows: List<Pair<MercatorWindow, Double>>, read: (MercatorWindow) -> ElevationGrid): Terrain =
            Terrain(windows.map { (w, until) -> TerrainLayer(w, read(w), until) })
    }
}

/**
 * Rays over a [Terrain] (T181): from a start point at an eye elevation, the highest apparent elevation angle of the
 * ground along a horizontal direction, with Earth's curvature and terrestrial refraction (coefficient [k]): a sample
 * `s` metres out at elevation `h` is seen at tangent `(h - s²(1 - k)/2R - eye) / s`. Steps start at one cell and grow
 * by [growth] of the distance (a step of 2 percent: 200 m at 10 km, where a coarse layer's cells are 50 m or more),
 * up to [rangeM]. One instance per thread ([steps], [distanceM] and [reachedM] are the last ray's).
 */
class RayMarch(
    val terrain: Terrain,
    val rangeM: Double = Horizon.DEFAULT_RANGE_M,
    val growth: Double = Horizon.STEP_GROWTH,
    val k: Double = Horizon.REFRACTION_K,
) {
    init {
        require(rangeM > 0) { "rangeM must be positive" }
        require(growth >= 0) { "growth must be >= 0" }
    }

    private val curvature = (1 - k) / (2 * Horizon.EARTH_RADIUS_M)

    /** Samples taken (with data), over every ray so far. */
    var steps = 0L

    /** Distance (m) of the last ray's highest sample; 0 when it had none. */
    var distanceM = 0.0
        private set

    /** Blocks skipped (the ground in them stood below the ray's line), over every ray so far. */
    var skips = 0L

    private val block = DoubleArray(2)

    /** Distance (m) of the last ray's farthest sample with data (an early exit stops short of the data's end: [coverageM] has that). */
    var reachedM = 0.0
        private set

    /**
     * The highest tangent of the elevation angle along the ray from global pixel ([gx], [gy]) at [eyeM] metres,
     * toward the horizontal unit direction ([east], [north]), with [pxPerM] reference pixels per metre at the start
     * and a first (and smallest) step of [minStepM]. Stops early once the tangent reaches [stopTan] (a cast shadow
     * needs no more), or once no sample farther out could beat the larger of the best so far and [floorTan] (the
     * ground's highest point, lowered by the curvature, would be seen below it). Negative infinity when the ray had
     * no sample with data.
     *
     * Blocks of ground lying wholly below the line the ray must beat ([TerrainLayer.maxLevels], 64 down to 4 cells a
     * side) are skipped without sampling: no sample in them could raise the answer. The result depends only on the
     * start, the direction and the terrain's values, so two windows over the same ground still agree cell for cell.
     */
    fun maxTangent(
        gx: Double, gy: Double, eyeM: Double, east: Double, north: Double, pxPerM: Double, minStepM: Double,
        stopTan: Double = Double.POSITIVE_INFINITY, floorTan: Double = Double.NEGATIVE_INFINITY, rangeM: Double = this.rangeM,
    ): Double {
        val dz = terrain.maxElevation - eyeM
        val stepX = east * pxPerM
        val stepY = -north * pxPerM
        var best = Double.NEGATIVE_INFINITY
        var bestS = 0.0
        var reached = 0.0
        var s = minStepM
        var n = 0L
        var skipped = 0L
        march@ while (s <= rangeM) {
            val line = max(best, floorTan)
            if ((dz - curvature * s * s) / s <= line) break
            val px = gx + stepX * s
            val py = gy + stepY * s
            if (line > Double.NEGATIVE_INFINITY) {
                var level = TerrainLayer.SKIP_LEVELS
                while (level >= 2) {
                    if (!terrain.block(px, py, s * pxPerM, east, -north, level, block)) break
                    val exitM = block[1] / pxPerM
                    if (exitM > minStepM * 0.5) {
                        // The line the ground must clear, eye + line*s + curvature*s^2, is convex in s: its lowest
                        // point over the block is at an end or at its vertex.
                        val sEnd = s + exitM
                        var floorLine = minOf(eyeM + line * s + curvature * s * s, eyeM + line * sEnd + curvature * sEnd * sEnd)
                        val vertex = -line / (2 * curvature)
                        if (vertex > s && vertex < sEnd) floorLine = minOf(floorLine, eyeM + line * vertex + curvature * vertex * vertex)
                        if (block[0] < floorLine) {
                            skipped++
                            s = sEnd + 0.01 // past the side, so a ray going west or north lands in the next block
                            continue@march
                        }
                    }
                    level -= 2
                }
            }
            val v = terrain.sample(px, py, s * pxPerM)
            if (v == Terrain.OUTSIDE) break
            if (!v.isNaN()) {
                n++
                reached = s
                val t = (v - curvature * s * s - eyeM) / s
                if (t > best) {
                    best = t
                    bestS = s
                    if (best >= stopTan) break
                }
            }
            s += max(minStepM, s * growth)
        }
        steps += n
        skips += skipped
        distanceM = bestS
        reachedM = reached
        return best
    }

    /** How far (m, at most [rangeM]) the terrain's layers reach along the ray from ([gx], [gy]) toward ([east], [north]). */
    fun coverageM(gx: Double, gy: Double, east: Double, north: Double, pxPerM: Double, rangeM: Double = this.rangeM): Double =
        minOf(rangeM, terrain.exitPx(gx, gy, east, -north) / pxPerM)
}

/**
 * The horizon seen from a point, one bin per bearing (T181): [angleDeg] is the highest apparent elevation angle of the
 * terrain toward bearing `i * stepDeg` (clockwise from true north; negative from a summit, whose horizon dips below
 * level), [distanceM] how far away that highest ground is, [reachedM] how far the data reached along that bearing
 * (less than [rangeM] where the terrain's layers end). Bearings without any data are flat (angle 0, distance 0) and
 * counted in [bearingsWithoutData].
 */
class HorizonProfile(
    val lat: Double,
    val lon: Double,
    /** Ground elevation at the point, metres (EGM2008). */
    val groundM: Double,
    val observerHeightM: Double,
    val stepDeg: Double,
    val rangeM: Double,
    val angleDeg: DoubleArray,
    val distanceM: DoubleArray,
    val reachedM: DoubleArray,
    /** Samples taken over all bearings. */
    val samples: Long,
    val bearingsWithoutData: Int,
) {
    val size: Int get() = angleDeg.size

    fun bearingDeg(i: Int): Double = i * stepDeg

    /** The horizon angle toward any [bearingDeg], linear between the two nearest bins. */
    fun angleAt(bearingDeg: Double): Double {
        val b = ((bearingDeg % 360) + 360) % 360 / stepDeg
        val i = floor(b).toInt() % size
        val f = b - floor(b)
        return angleDeg[i] + (angleDeg[(i + 1) % size] - angleDeg[i]) * f
    }

    /** Distance of the highest ground toward [bearingDeg] (the nearest bin). */
    fun distanceAt(bearingDeg: Double): Double = distanceM[(((bearingDeg % 360) + 360) % 360 / stepDeg).roundToInt() % size]

    /** Index of the highest bin. */
    val highest: Int get() = angleDeg.indices.maxBy { angleDeg[it] }
}

/**
 * The terrain horizon around a point (T181), written once for the sun-and-shade point answer and the viewshed
 * (T182): from a point, for bearings every `stepDeg`, the highest apparent elevation angle of the ground with the
 * distance it comes from, over a [Terrain] of a fine layer near and a coarser one out to the range, with Earth's
 * curvature and refraction coefficient [REFRACTION_K] (0.13, the standard terrestrial value).
 *
 * What T182 calls: [windowsAround] for the windows to read around a point (a fine window [NEAR_CELLS] cells out, a
 * window [COARSE_ZOOMS] zooms coarser out to the range), [terrainAround] to read them (`DemPyramid::window` on the
 * phone), then [sweep] for the [HorizonProfile]. For a visibility test along one bearing (is the ground at distance d
 * seen?), [RayMarch.maxTangent] over the same terrain gives the highest tangent between the point and any distance
 * (`rangeM` = d), the test being the target's own tangent against it; [dropM] is the curvature term both use.
 */
object Horizon {
    /** Terrestrial refraction coefficient: the line of sight curves down with 0.13 of Earth's curvature. */
    const val REFRACTION_K = 0.13

    /** Mean Earth radius, metres. */
    const val EARTH_RADIUS_M = 6_371_000.0

    /** Bearing step of a sweep, degrees. */
    const val DEFAULT_STEP_DEG = 1.0

    /**
     * How far out a ray looks, metres. 30 km: a 3,000 m relief at that distance stands 5.7 degrees above level, and
     * the curvature drop there (61 m) is already part of the answer.
     */
    const val DEFAULT_RANGE_M = 30_000.0

    /** A ray's step grows by this share of its distance (after a first run of one-cell steps). */
    const val STEP_GROWTH = 0.02

    /**
     * The fine layer is used this many of its cells out from a ray's start; the coarse layer beyond. Measured on
     * Rainier at z13 (T181): against a reference march (40 km, steps of 0.5 percent, a coarse layer 2 zooms down,
     * 128 near cells), 64 near cells left 0.67 percent of the cells sunlit that the reference shades, 128 left
     * 0.19 percent; the step's growth (1, 2 or 4 percent: 0.65, 0.67, 0.77) and the range (30 or 40 km: equal)
     * mattered far less.
     */
    const val NEAR_CELLS = 128

    /** The coarse layer is this many zooms coarser than the fine one (8 times the cell). */
    const val COARSE_ZOOMS = 3

    /** Eye height of a standing person, metres (the point answer and the viewshed). */
    const val EYE_HEIGHT_M = 1.7

    private const val DEG = PI / 180

    /** How far the line of sight drops below the level at the start over [distanceM]: curvature less refraction. */
    fun dropM(distanceM: Double, k: Double = REFRACTION_K): Double = distanceM * distanceM * (1 - k) / (2 * EARTH_RADIUS_M)

    /**
     * The windows a sweep from ([lat], [lon]) needs, each with its band ([TerrainLayer.untilPx]): the fine one at
     * [zoom] reaching [NEAR_CELLS] cells (plus two) around the point, and when [COARSE_ZOOMS] coarser exists, one
     * around the point out to [rangeM].
     */
    fun windowsAround(lat: Double, lon: Double, zoom: Int, rangeM: Double = DEFAULT_RANGE_M, tileSize: Int = 256, coarseZooms: Int = COARSE_ZOOMS): List<Pair<MercatorWindow, Double>> {
        val gx = WebMercator.lonToX(lon, zoom) * tileSize
        val gy = WebMercator.latToY(lat, zoom) * tileSize
        val out = ArrayList<Pair<MercatorWindow, Double>>()
        fun square(z: Int, radiusPx: Int): MercatorWindow {
            val scale = 1.0 / (1L shl (zoom - z))
            val cx = floor(gx * scale).toInt()
            val cy = floor(gy * scale).toInt()
            return MercatorWindow(z, cx - radiusPx, cy - radiusPx, 2 * radiusPx + 1, 2 * radiusPx + 1, tileSize)
        }
        val coarse = if (coarseZooms > 0) zoom - coarseZooms else -1
        out += square(zoom, NEAR_CELLS + 2) to (if (coarse >= 0) NEAR_CELLS.toDouble() else Double.POSITIVE_INFINITY)
        if (coarse >= 0) {
            val mpp = WebMercator.metersPerPixel(lat, coarse, tileSize)
            out += square(coarse, ceil(rangeM / mpp).toInt() + 2) to Double.POSITIVE_INFINITY
        }
        return out
    }

    /** The terrain of [windowsAround], each window read by [read]. */
    fun terrainAround(lat: Double, lon: Double, zoom: Int, rangeM: Double = DEFAULT_RANGE_M, tileSize: Int = 256, coarseZooms: Int = COARSE_ZOOMS, read: (MercatorWindow) -> ElevationGrid): Terrain =
        Terrain.read(windowsAround(lat, lon, zoom, rangeM, tileSize, coarseZooms), read)

    /**
     * The horizon from ([lat], [lon]) at [observerHeightM] above the ground: one ray per bearing every [stepDeg]
     * (360 / [stepDeg] bins, so the step should divide 360), each out to [rangeM] over [terrain] ([RayMarch]).
     * Throws when the terrain has no data at the point itself.
     */
    fun sweep(
        terrain: Terrain, lat: Double, lon: Double, stepDeg: Double = DEFAULT_STEP_DEG, rangeM: Double = DEFAULT_RANGE_M,
        observerHeightM: Double = 0.0, k: Double = REFRACTION_K,
    ): HorizonProfile {
        require(stepDeg > 0 && stepDeg <= 90) { "stepDeg must be in (0, 90]" }
        val n = (360 / stepDeg).roundToInt()
        val gx = terrain.gx(lon)
        val gy = terrain.gy(lat)
        val ground = terrain.sample(gx, gy)
        require(ground != Terrain.OUTSIDE && !ground.isNaN()) { "no elevation at $lat, $lon" }
        val eye = ground + observerHeightM
        val mpp = WebMercator.metersPerPixel(lat, terrain.zoom, terrain.tileSize)
        val march = RayMarch(terrain, rangeM, STEP_GROWTH, k)
        val angle = DoubleArray(n)
        val dist = DoubleArray(n)
        val reached = DoubleArray(n)
        var empty = 0
        for (i in 0 until n) {
            val b = i * 360.0 / n * DEG
            val t = march.maxTangent(gx, gy, eye, sin(b), cos(b), 1 / mpp, mpp)
            if (t == Double.NEGATIVE_INFINITY) {
                empty++
                reached[i] = march.coverageM(gx, gy, sin(b), cos(b), 1 / mpp)
                continue
            }
            angle[i] = atan(t) / DEG
            dist[i] = march.distanceM
            reached[i] = march.coverageM(gx, gy, sin(b), cos(b), 1 / mpp)
        }
        return HorizonProfile(lat, lon, ground, observerHeightM, 360.0 / n, rangeM, angle, dist, reached, march.steps, empty)
    }

    /** Tangent of an elevation angle in degrees. */
    internal fun tanDeg(deg: Double): Double = tan(deg * DEG)
}
