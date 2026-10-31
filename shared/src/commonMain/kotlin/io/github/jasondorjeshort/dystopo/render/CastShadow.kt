package io.github.jasondorjeshort.dystopo.render

import io.github.jasondorjeshort.dystopo.dem.Horizon
import io.github.jasondorjeshort.dystopo.dem.RayMarch
import io.github.jasondorjeshort.dystopo.dem.Terrain
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.nav.Sun
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.time.TimeSource

/**
 * Cast shadows on the terrain at an instant (T181), beside [Hillshade] (which lights faces from an azimuth and
 * altitude but casts no shadow): every cell of a window marches toward the sun over a [Terrain] ([RayMarch]) and is in
 * shade when any ground along the way stands above the sun's line, Earth's curvature and refraction included. Faces
 * turned away from the sun shade themselves (the first steps already rise above the line), so the result is the
 * whole shade, attached and cast. With the sun at or below the flat horizon everything is shade (night counted as
 * shade, the survey's convention for the overlay). Each cell takes its own sun from a [SunField] (one sun for a
 * whole window is the other form, for tests and goldens), so windows cut from one world agree along their edges.
 *
 * Far ridges: the terrain is a fine layer reaching [Horizon.NEAR_CELLS] cells toward the sun and a layer
 * [Horizon.COARSE_ZOOMS] zooms coarser out to the range ([windowsFor]), the range capped by how far a ray can still
 * meet ground ([rangeFor]: the highest relief [RELIEF_M] over the tangent of the sun's altitude, at most
 * [Horizon.DEFAULT_RANGE_M]). A ray also stops as soon as the highest ground of the layers, seen from there, would
 * stand below the sun's line.
 */
object CastShadow {
    /** The relief a far ridge can stand above a cell (metres): beyond `RELIEF_M / tan(altitude)` no ground can shade it. */
    const val RELIEF_M = 4_000.0

    private const val DEG = PI / 180

    /**
     * One window's shade: [shade] is 1 in shade, 0 in sun, NaN without data, row-major over the window, with
     * counters. [night] when the sun was at or below the horizon (every cell with data in shade, no ray marched).
     */
    class Result(
        val width: Int,
        val height: Int,
        val shade: FloatArray,
        val shadedCells: Int,
        val litCells: Int,
        val noDataCells: Int,
        val night: Boolean,
        /** Samples taken over all rays. */
        val steps: Long,
        val ms: Double,
    ) {
        val shadedFraction: Double get() = if (shadedCells + litCells == 0) 0.0 else shadedCells.toDouble() / (shadedCells + litCells)
    }

    /** The farthest a ray toward a sun at [altitudeDeg] can meet ground standing [RELIEF_M] higher, capped at [capM]. */
    fun rangeFor(altitudeDeg: Double, capM: Double = Horizon.DEFAULT_RANGE_M): Double =
        if (altitudeDeg <= 0) capM else min(capM, RELIEF_M / tan(altitudeDeg * DEG))

    /**
     * The terrain windows the shade of [target] needs for a sun at [azimuthDeg], each with its band
     * ([io.github.jasondorjeshort.dystopo.dem.TerrainLayer.untilPx], reference pixels at the target's zoom): the target
     * grown by [nearCells] (plus two) on its sunward sides at the target's zoom, and a window [coarseZooms] coarser
     * reaching [rangeM] toward the sun. A sun due east grows the windows east only.
     */
    fun windowsFor(
        target: MercatorWindow, azimuthDeg: Double, rangeM: Double,
        nearCells: Int = Horizon.NEAR_CELLS, coarseZooms: Int = Horizon.COARSE_ZOOMS,
    ): List<Pair<MercatorWindow, Double>> {
        val east = sin(azimuthDeg * DEG)
        val north = cos(azimuthDeg * DEG)
        fun grown(w: MercatorWindow, by: Double): MercatorWindow {
            val dx = ceil(by * kotlin.math.abs(east)).toInt() + 2
            val dy = ceil(by * kotlin.math.abs(north)).toInt() + 2
            val left = if (east < 0) dx else 2
            val right = if (east > 0) dx else 2
            val top = if (north > 0) dy else 2
            val bottom = if (north < 0) dy else 2
            return w.copy(x0 = w.x0 - left, y0 = w.y0 - top, width = w.width + left + right, height = w.height + top + bottom)
        }
        val z = target.zoom
        val coarse = z - coarseZooms
        val fine = grown(target, nearCells.toDouble()) to (if (coarse >= 0 && coarseZooms > 0) nearCells.toDouble() else Double.POSITIVE_INFINITY)
        if (coarse < 0 || coarseZooms <= 0) return listOf(fine)
        // The coarse window: the target in coarse pixels (rounded outward), grown by the range at the window's
        // largest scale (its edge nearest the equator has the most pixels per metre).
        val shrink = 1 shl coarseZooms
        val cx0 = (target.x0).floorDiv(shrink)
        val cy0 = (target.y0).floorDiv(shrink)
        val cx1 = (target.x0 + target.width - 1).floorDiv(shrink)
        val cy1 = (target.y0 + target.height - 1).floorDiv(shrink)
        val base = MercatorWindow(coarse, cx0, cy0, cx1 - cx0 + 1, cy1 - cy0 + 1, target.tileSize)
        val nearestEquator = min(kotlin.math.abs(target.latAt(0)), kotlin.math.abs(target.latAt(target.height - 1)))
        val rangePx = rangeM / WebMercator.metersPerPixel(nearestEquator, coarse, target.tileSize)
        return listOf(fine, grown(base, rangePx) to Double.POSITIVE_INFINITY)
    }

    /**
     * The windows [target] needs under a [SunField]: the union, layer by layer, of [windowsFor] at nine points across
     * the target (its corners, edge middles and centre; the direction turns by hundredths of a degree across a tile),
     * each with the range its own altitude asks ([rangeFor], at most [capM]); just the target when the sun is down at
     * all nine.
     */
    fun windowsFor(
        target: MercatorWindow, field: SunField, capM: Double = Horizon.DEFAULT_RANGE_M,
        nearCells: Int = Horizon.NEAR_CELLS, coarseZooms: Int = Horizon.COARSE_ZOOMS,
    ): List<Pair<MercatorWindow, Double>> {
        val sun = DoubleArray(3)
        var union: List<Pair<MercatorWindow, Double>>? = null
        for (a in 0..2) for (b in 0..2) {
            field.at(target.zoom, target.tileSize, target.x0 + target.width * a / 2.0, target.y0 + target.height * b / 2.0, sun)
            if (sun[2] <= 0) continue
            val ws = windowsFor(target, SunField.azimuthDeg(sun), rangeFor(sun[2], capM), nearCells, coarseZooms)
            union = union?.zip(ws) { (u, until), (w, _) -> bounding(u, w) to until } ?: ws
        }
        return union ?: listOf(target to Double.POSITIVE_INFINITY)
    }

    private fun bounding(a: MercatorWindow, b: MercatorWindow): MercatorWindow {
        val x0 = min(a.x0, b.x0)
        val y0 = min(a.y0, b.y0)
        return a.copy(x0 = x0, y0 = y0, width = max(a.x0 + a.width, b.x0 + b.width) - x0, height = max(a.y0 + a.height, b.y0 + b.height) - y0)
    }

    /**
     * The shade of every cell of [target] (at the terrain's reference zoom) for a sun at [azimuthDeg] (clockwise from
     * true north) and apparent altitude [altitudeDeg] (the centre's, refraction included: [Sun.Position.apparentAltitudeDeg])
     * the same over the whole window, rays out to [rangeM], their steps growing by [growth] of the distance. Pure and
     * thread-safe (one [RayMarch] per call). The tiles and `devtool shade` use the [SunField] form.
     */
    fun compute(
        terrain: Terrain, target: MercatorWindow, azimuthDeg: Double, altitudeDeg: Double, rangeM: Double = rangeFor(altitudeDeg),
        growth: Double = Horizon.STEP_GROWTH,
    ): Result {
        val east = sin(azimuthDeg * DEG)
        val north = cos(azimuthDeg * DEG)
        return run(terrain, target, growth, rangeM, false) { _, _, out ->
            out[0] = east
            out[1] = north
            out[2] = altitudeDeg
        }
    }

    /**
     * The shade of every cell of [target] under [field]: each cell's own sun (direction and altitude interpolated from
     * the field's lattice), its rays out to [rangeFor] its altitude (at most [capM]). A cell's answer depends on its
     * place, the instant and the terrain only, so tiles cut from one world agree along their edges exactly.
     */
    fun compute(terrain: Terrain, target: MercatorWindow, field: SunField, growth: Double = Horizon.STEP_GROWTH, capM: Double = Horizon.DEFAULT_RANGE_M): Result =
        run(terrain, target, growth, capM, true) { gx, gy, out -> field.at(target.zoom, target.tileSize, gx, gy, out) }

    private inline fun run(
        terrain: Terrain, target: MercatorWindow, growth: Double, rangeM: Double, perCellRange: Boolean,
        sunAt: (Double, Double, DoubleArray) -> Unit,
    ): Result {
        require(target.zoom == terrain.zoom) { "target zoom ${target.zoom} is not the terrain's ${terrain.zoom}" }
        val clock = TimeSource.Monotonic.markNow()
        val w = target.width
        val h = target.height
        val shade = FloatArray(w * h)
        val march = RayMarch(terrain, rangeM, growth)
        val sun = DoubleArray(3)
        var shaded = 0
        var lit = 0
        var noData = 0
        var dark = 0
        for (j in 0 until h) {
            val gy = target.y0 + j + 0.5
            val lat = WebMercator.yToLat(gy / target.tileSize, target.zoom)
            val mpp = WebMercator.metersPerPixel(lat, target.zoom, target.tileSize)
            for (i in 0 until w) {
                val gx = target.x0 + i + 0.5
                val ground = terrain.sample(gx, gy)
                val k = j * w + i
                if (ground.isNaN() || ground == Terrain.OUTSIDE) {
                    shade[k] = Float.NaN
                    noData++
                    continue
                }
                sunAt(gx, gy, sun)
                val alt = sun[2]
                val inShade = if (alt <= 0) {
                    dark++
                    true
                } else {
                    val tanSun = tan(alt * DEG)
                    val range = if (perCellRange) rangeFor(alt, rangeM) else rangeM
                    march.maxTangent(gx, gy, ground, sun[0], sun[1], 1 / mpp, mpp, stopTan = tanSun, floorTan = tanSun, rangeM = range) >= tanSun
                }
                if (inShade) {
                    shade[k] = 1f
                    shaded++
                } else {
                    lit++
                }
            }
        }
        val night = dark > 0 && dark == shaded + lit
        return Result(w, h, shade, shaded, lit, noData, night, march.steps, clock.elapsedNow().inWholeMicroseconds / 1000.0)
    }

    /** [compute] under the [SunField] of [epochMs]. */
    fun at(terrain: Terrain, target: MercatorWindow, epochMs: Long): Result = compute(terrain, target, SunField(epochMs))

    /** The sun at [epochMs] seen from [window]'s centre. */
    fun sunAt(window: MercatorWindow, epochMs: Long): Sun.Position =
        Sun.position(window.latAt(window.height / 2), window.lonAt(window.width / 2), epochMs)

    /** The lattice of a [SunField]: the corners of this zoom's tiles. */
    const val SUN_LATTICE_ZOOM = 10

    /**
     * The sun over the world at one instant [epochMs] (T181), so that every window marches the same rays: the sun's
     * horizontal direction (east and north components) and apparent altitude computed at the corners of
     * [latticeZoom]'s tiles (about 27 km apart at 47 N for zoom 10) and interpolated bilinearly to each cell. A cell's
     * sun is then a function of its place alone, whichever window it is marched in; the interpolation is off the exact
     * sun by well under a hundredth of a degree. Not thread-safe (a cache of corners): one per window.
     */
    class SunField(val epochMs: Long, val latticeZoom: Int = SUN_LATTICE_ZOOM) {
        private val corners = HashMap<Long, DoubleArray>()

        private fun corner(ix: Int, iy: Int): DoubleArray = corners.getOrPut((ix.toLong() shl 32) or (iy.toLong() and 0xffffffffL)) {
            val p = Sun.position(WebMercator.yToLat(iy.toDouble(), latticeZoom), WebMercator.xToLon(ix.toDouble(), latticeZoom), epochMs)
            doubleArrayOf(sin(p.azimuthDeg * DEG), cos(p.azimuthDeg * DEG), p.apparentAltitudeDeg)
        }

        /**
         * Into [out]: the east and north components of the sun's horizontal direction (a unit vector) and its apparent
         * altitude in degrees, at global pixel ([gx], [gy]) of [zoom].
         */
        fun at(zoom: Int, tileSize: Int, gx: Double, gy: Double, out: DoubleArray) {
            val d = latticeZoom - zoom
            val scale = (if (d >= 0) (1L shl d).toDouble() else 1.0 / (1L shl -d)) / tileSize
            val u = gx * scale
            val v = gy * scale
            val ix = floor(u).toInt()
            val iy = floor(v).toInt()
            val fx = u - ix
            val fy = v - iy
            val a = corner(ix, iy)
            val b = corner(ix + 1, iy)
            val c = corner(ix, iy + 1)
            val e = corner(ix + 1, iy + 1)
            for (k in 0..2) {
                val top = a[k] + (b[k] - a[k]) * fx
                out[k] = top + ((c[k] + (e[k] - c[k]) * fx) - top) * fy
            }
            val n = sqrt(out[0] * out[0] + out[1] * out[1])
            if (n > 0) {
                out[0] /= n
                out[1] /= n
            }
        }

        companion object {
            /** The azimuth (degrees clockwise from north) of a direction [at] wrote. */
            fun azimuthDeg(sun: DoubleArray): Double = (atan2(sun[0], sun[1]) / DEG + 360) % 360
        }
    }
}
