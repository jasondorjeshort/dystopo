package io.github.jasondorjeshort.dystopo.tiles

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.render.Hillshade
import io.github.jasondorjeshort.dystopo.render.Slope
import kotlin.jvm.Synchronized
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.time.TimeSource

/** Hillshade style: none, one light from the north-west, or the multidirectional blend ([Hillshade]). */
enum class HillshadeMode(val key: String) {
    OFF("off"), SINGLE("single"), MULTI("multi");

    companion object {
        fun parse(s: String): HillshadeMode? = entries.firstOrNull { it.key == s.trim().lowercase() }
    }
}

/**
 * What a shade tile draws: a hillshade overlay ([hillshade], [intensity] 0..1 scales its opacity), the
 * CalTopo-style slope bands ([Slope.bands]) on top when [slope], and dimmer colours for a dark base
 * when [night].
 */
data class ShadeOptions(
    val hillshade: HillshadeMode,
    val intensity: Double = 1.0,
    val slope: Boolean = false,
    val night: Boolean = false,
) {
    init {
        require(intensity in 0.0..1.0) { "intensity must be in 0..1" }
    }

    /** Short, stable key for cache directories and tile URLs: changes whenever the pixels would. */
    val key: String
        get() = "${ShadeTiles.VERSION}-${hillshade.key}${(intensity * 100).roundToInt()}-${if (slope) "slope" else "noslope"}-${if (night) "night" else "day"}"
}

/**
 * How shade tile z/x/y is made: the DEM zoom sampled ([sampleZoom] = z - [reduction]), the grid of
 * [gridTileSize] cells across that covers the tile there, and the sampled window (tile plus [halo]) in
 * global pixels of [sampleZoom]. Past the pack's deepest zoom the shade is computed on the deepest DEM
 * and its values interpolated up to the tile's pixels, so overzoomed relief stays smooth instead of
 * showing the facets of a bilinear surface.
 */
data class ShadeTilePlan(
    val z: Int,
    val x: Int,
    val y: Int,
    val sampleZoom: Int,
    val reduction: Int,
    val gridTileSize: Int,
    val halo: Int,
    val windowX: Int,
    val windowY: Int,
) {
    val windowSize: Int get() = gridTileSize + 2 * halo
}

/** One rendered shade tile: [argb] (non-premultiplied, row-major, [size] square) plus counters. */
class ShadeTile(
    val size: Int,
    val argb: IntArray,
    /** Pixels left transparent because the DEM has no data there. */
    val noDataPixels: Int,
    /** Pixels tinted by a slope band. */
    val slopePixels: Int,
    /** Gradient, hillshade and slope, one fused pass over the window. */
    val fieldMs: Double,
    val composeMs: Double,
)

/**
 * Raster hillshade and slope-angle tiles from a [DemPyramid] (T47): the on-device path for the map
 * options that MapLibre's own `raster-dem` hillshade cannot draw (multidirectional light, an intensity,
 * the slope bands, night colours). Tiles are [TILE_SIZE] px PNG-ready ARGB overlays, transparent where
 * flat ground needs no shading, drawn by MapLibre as a `raster` layer over the land cover.
 *
 * Each tile samples its DEM window with a [HALO] of real neighbouring cells, so the Horn gradient at
 * the tile edge sees the same cells as the neighbour does and edges match exactly; the zoom choice
 * ([plan]) follows the pack like [ContourTiles.plan] does: the tile's own zoom while the pack has it,
 * the deepest pack zoom (interpolated up) beyond.
 */
object ShadeTiles {
    const val TILE_SIZE = 256

    /** Cells sampled past each edge: one for the Horn kernel, one more for interpolation when overzoomed. */
    const val HALO = 2

    /** Deepest overzoom computed from the pack's deepest DEM; beyond it the pyramid's bilinear overzoom is sampled. */
    const val MAX_REDUCTION = 4

    /** Bumped when the pixels for the same options change, so caches and URLs move on. */
    const val VERSION = "s1"

    /** Opacity of the slope-band tint (the devtool's `--slope-opacity` default is 0.5). */
    const val SLOPE_OPACITY = 0.45
    const val SLOPE_OPACITY_NIGHT = 0.35

    private val flat = sqrt(0.5) // sin(45 degrees): the shade of flat ground

    /** The plan for tile z/x/y over a pack whose deepest zoom is [demMaxZoom]. */
    fun plan(z: Int, x: Int, y: Int, demMaxZoom: Int, tileSize: Int = TILE_SIZE): ShadeTilePlan {
        val r = (z - demMaxZoom).coerceIn(0, MAX_REDUCTION)
        val grid = tileSize shr r
        require(grid >= 8 && (grid shl r) == tileSize) { "tileSize $tileSize too small for reduction $r" }
        return ShadeTilePlan(z, x, y, z - r, r, grid, HALO, x * grid - HALO, y * grid - HALO)
    }

    /**
     * Vertical exaggeration by sampled zoom: 1 at z13 and deeper, growing at coarser zooms, where cells
     * of hundreds of metres flatten the gradient (MapLibre's hillshade does the same), capped at 4.
     */
    fun zFactor(sampleZoom: Int): Double = 2.0.pow((13 - sampleZoom) * 0.4).coerceIn(1.0, 4.0)

    /** Samples the plan's window from [dem]: the grid [render] expects. Thread-safe when [dem] is. */
    fun sample(dem: DemPyramid, plan: ShadeTilePlan): ElevationGrid =
        dem.window(plan.sampleZoom, plan.windowX, plan.windowY, plan.windowSize, plan.windowSize)

    /** Samples and renders tile z/x/y; see [render]. */
    fun generate(dem: DemPyramid, z: Int, x: Int, y: Int, options: ShadeOptions): ShadeTile {
        val plan = plan(z, x, y, dem.maxZoom, TILE_SIZE)
        return render(sample(dem, plan), plan, options)
    }

    /**
     * Renders a shade tile from [grid], the window of [plan]. Pure and thread-safe.
     *
     * Hillshade becomes an overlay: faces darker than flat ground get a shadow colour with opacity
     * growing to [intensity][ShadeOptions.intensity] times a maximum at full shadow, faces brighter than
     * flat get a soft highlight, flat ground stays transparent so the land cover shows unchanged. The
     * slope bands are blended over that. At night the shadow is black and the highlight nearly gone
     * (dimmed relief on a dark base).
     */
    fun render(grid: ElevationGrid, plan: ShadeTilePlan, options: ShadeOptions): ShadeTile {
        val w = plan.windowSize
        require(grid.width == w && grid.height == w) { "grid ${grid.width}x${grid.height} is not the plan's ${w}x$w window" }
        val clock = TimeSource.Monotonic
        val t0 = clock.markNow()
        val mode = if (options.intensity > 0.0) options.hillshade else HillshadeMode.OFF
        val f = field(grid, zFactor(plan.sampleZoom), mode, options.slope)
        val shade = f.shade
        val slope = f.tan2
        val t2 = clock.markNow()

        val size = plan.gridTileSize shl plan.reduction
        val out = IntArray(size * size)
        val scale = (1 shl plan.reduction).toDouble()
        val h = plan.halo
        // Source coordinate per output column/row (pixel centres), the same for x and y.
        val lo = IntArray(size)
        val frac = FloatArray(size)
        for (i in 0 until size) {
            val c = ((i + 0.5) / scale - 0.5 + h).coerceIn(0.0, (w - 1).toDouble())
            val a = floor(c).toInt().coerceAtMost(w - 2)
            lo[i] = a
            frac[i] = (c - a).toFloat()
        }
        val night = options.night
        val shadowRgb = if (night) 0x000000 else 0x3a3029
        val highlightRgb = if (night) 0xb8b0a0 else 0xfffdf7
        val maxShadow = (if (night) 0.6 else 0.7) * options.intensity
        val maxHighlight = (if (night) 0.08 else 0.5) * options.intensity
        val slopeOpacity = if (night) SLOPE_OPACITY_NIGHT else SLOPE_OPACITY
        val bandRgb = IntArray(Slope.bands.size) { Slope.bands[it].rgb }
        val bandTan2 = bandTan2()
        var noData = 0
        var tinted = 0
        for (j in 0 until size) {
            val ya = lo[j]
            val fy = frac[j]
            for (i in 0 until size) {
                val xa = lo[i]
                val fx = frac[i]
                val k = ya * w + xa
                if (grid.values[k].isNaN() || grid.values[k + 1].isNaN() || grid.values[k + w].isNaN() || grid.values[k + w + 1].isNaN()) {
                    noData++
                    continue // transparent
                }
                var a = 0.0
                var rgb = 0
                if (shade != null) {
                    val s = lerp2(shade, k, w, fx, fy)
                    if (s < flat) {
                        a = maxShadow * (flat - s) / flat
                        rgb = shadowRgb
                    } else {
                        a = maxHighlight * (s - flat) / (1 - flat)
                        rgb = highlightRgb
                    }
                }
                if (slope != null) {
                    val band = band(lerp2(slope, k, w, fx, fy), bandTan2)
                    if (band >= 0) {
                        tinted++
                        // "over": the tint on top of the hillshade overlay, both non-premultiplied.
                        val a2 = slopeOpacity
                        val outA = a2 + a * (1 - a2)
                        rgb = mix(bandRgb[band], a2, rgb, a * (1 - a2), outA)
                        a = outA
                    }
                }
                val alpha = (a * 255).roundToInt().coerceIn(0, 255)
                out[j * size + i] = if (alpha == 0) 0 else (alpha shl 24) or (rgb and 0xFFFFFF)
            }
        }
        val t3 = clock.markNow()
        return ShadeTile(
            size, out, noData, tinted,
            fieldMs = (t2 - t0).inWholeMicroseconds / 1000.0,
            composeMs = (t3 - t2).inWholeMicroseconds / 1000.0,
        )
    }

    /** Per-cell hillshade (0..1) and squared slope tangent of a window; NaN where the grid has no data. */
    internal class Field(val shade: FloatArray?, val tan2: FloatArray?)

    /**
     * The Horn gradient, the hillshade of [mode] (with vertical exaggeration [zFactor]) and the squared slope
     * tangent (true slope, no exaggeration) in one pass. The same numbers as [Gradient], [Hillshade.compute]
     * and [Hillshade.multidirectional] (edges clamped, no-data neighbours replaced by the centre cell; a
     * unit test holds them equal), without the intermediate gradient arrays or per-light objects, which
     * made up most of a tile's time on the phone.
     */
    internal fun field(grid: ElevationGrid, zFactor: Double, mode: HillshadeMode, slope: Boolean): Field {
        val w = grid.width
        val h = grid.height
        val v = grid.values
        val n = w * h
        val shade = if (mode == HillshadeMode.OFF) null else FloatArray(n)
        val tan2 = if (slope) FloatArray(n) else null
        if (shade == null && tan2 == null) return Field(null, null)
        val scale = 1.0 / (8.0 * grid.cellSizeMeters)
        // Lights: (east, north) unit direction, light vector, fixed bias. Single: the first entry, 315 degrees.
        val azimuths = if (mode == HillshadeMode.MULTI) Hillshade.multiAzimuths else doubleArrayOf(315.0)
        val bias = if (mode == HillshadeMode.MULTI) MULTI_BIAS else doubleArrayOf(0.0)
        val nl = azimuths.size
        val ux = DoubleArray(nl)
        val uy = DoubleArray(nl)
        val lx = DoubleArray(nl)
        val ly = DoubleArray(nl)
        val alt = 45.0 * kotlin.math.PI / 180.0
        val lz = kotlin.math.sin(alt)
        for (k in 0 until nl) {
            val az = azimuths[k] * kotlin.math.PI / 180.0
            ux[k] = kotlin.math.sin(az)
            uy[k] = kotlin.math.cos(az)
            lx[k] = ux[k] * kotlin.math.cos(alt)
            ly[k] = uy[k] * kotlin.math.cos(alt)
        }
        val multi = mode == HillshadeMode.MULTI
        for (y in 0 until h) {
            val ym = if (y > 0) y - 1 else 0
            val yp = if (y < h - 1) y + 1 else h - 1
            for (x in 0 until w) {
                val i = y * w + x
                val e = v[i]
                if (e.isNaN()) {
                    shade?.set(i, Float.NaN)
                    tan2?.set(i, Float.NaN)
                    continue
                }
                val xm = if (x > 0) x - 1 else 0
                val xp = if (x < w - 1) x + 1 else w - 1
                val a = nz(v[ym * w + xm], e)
                val b = nz(v[ym * w + x], e)
                val c = nz(v[ym * w + xp], e)
                val d = nz(v[y * w + xm], e)
                val f = nz(v[y * w + xp], e)
                val g = nz(v[yp * w + xm], e)
                val hh = nz(v[yp * w + x], e)
                val ii = nz(v[yp * w + xp], e)
                val dzdx = ((c + 2 * f + ii) - (a + 2 * d + g)) * scale
                val dzdy = -((g + 2 * hh + ii) - (a + 2 * b + c)) * scale
                val m2 = dzdx * dzdx + dzdy * dzdy
                if (tan2 != null) tan2[i] = m2.toFloat()
                if (shade == null) continue
                val sx = dzdx * zFactor
                val sy = dzdy * zFactor
                val s2 = m2 * zFactor * zFactor
                val invNorm = 1.0 / sqrt(s2 + 1.0)
                if (!multi) {
                    shade[i] = ((-sx * lx[0] - sy * ly[0] + lz) * invNorm).coerceAtLeast(0.0).toFloat()
                    continue
                }
                val gm = sqrt(s2)
                var sum = 0.0
                var weights = 0.0
                for (k in 0 until nl) {
                    val wk = bias[k] + if (gm == 0.0) 1.0 else {
                        val sn = (-sx * uy[k] + sy * ux[k]) / gm
                        sn * sn
                    }
                    sum += wk * ((-sx * lx[k] - sy * ly[k] + lz) * invNorm).coerceAtLeast(0.0)
                    weights += wk
                }
                shade[i] = if (weights > 0.0) (sum / weights).toFloat() else Float.NaN
            }
        }
        return Field(shade, tan2)
    }

    /** The fixed per-light weights of [Hillshade.multidirectional], in [Hillshade.multiAzimuths] order. */
    private val MULTI_BIAS = doubleArrayOf(0.0, 0.25, 0.75, 0.25)

    private fun nz(v: Float, centre: Float): Double = if (v.isNaN()) centre.toDouble() else v.toDouble()

    /** tan² of each band's lower edge, for [band]. */
    private fun bandTan2(): DoubleArray = DoubleArray(Slope.bands.size) {
        val t = kotlin.math.tan(Slope.bands[it].minDeg * kotlin.math.PI / 180.0)
        t * t
    }

    /** The [Slope.bands] index for a squared slope tangent, as [Slope.bandIndex] gives for its angle; -1 below the first. */
    private fun band(tan2: Double, edges: DoubleArray): Int {
        if (tan2.isNaN() || tan2 < edges[0]) return -1
        var k = 0
        while (k + 1 < edges.size && tan2 >= edges[k + 1]) k++
        return k
    }

    /** Bilinear value at cell [k] (top-left of the four) of a [w]-wide array. */
    private fun lerp2(v: FloatArray, k: Int, w: Int, fx: Float, fy: Float): Double {
        val top = v[k] + (v[k + 1] - v[k]) * fx
        val bottom = v[k + w] + (v[k + w + 1] - v[k + w]) * fx
        return (top + (bottom - top) * fy).toDouble()
    }

    /** Colour of `c1` with weight `w1` plus `c2` with weight `w2`, divided by [total] (alpha compositing). */
    private fun mix(c1: Int, w1: Double, c2: Int, w2: Double, total: Double): Int {
        if (total <= 0.0) return c1
        fun ch(shift: Int): Int = (((c1 shr shift) and 0xFF) * w1 + ((c2 shr shift) and 0xFF) * w2).div(total).roundToInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/**
 * Shade tile generation counters for `/stats` (`shadeGeneration`): per tile zoom min/avg/max
 * milliseconds with the sample/render split, and the most recent tiles. Thread-safe.
 */
class ShadeTileStats(private val keepRecent: Int = 24) {
    private class ZoomTiming {
        var count = 0L
        var total = 0.0
        var min = Double.POSITIVE_INFINITY
        var max = 0.0
    }

    private val byZoom = HashMap<Int, ZoomTiming>()
    private val recent = ArrayDeque<Map<String, Any?>>()
    private var count = 0L
    private var total = 0.0
    private var min = Double.POSITIVE_INFINITY
    private var max = 0.0

    /** Records one tile: [totalMs] from start of sampling to encoded bytes, with its parts. */
    @Synchronized
    fun add(plan: ShadeTilePlan, tile: ShadeTile, totalMs: Double, sampleMs: Double, encodeMs: Double, bytes: Int, options: ShadeOptions, waitMs: Double = 0.0) {
        count++
        total += totalMs
        if (totalMs < min) min = totalMs
        if (totalMs > max) max = totalMs
        val zt = byZoom.getOrPut(plan.z) { ZoomTiming() }
        zt.count++
        zt.total += totalMs
        if (totalMs < zt.min) zt.min = totalMs
        if (totalMs > zt.max) zt.max = totalMs
        recent.addLast(
            linkedMapOf(
                "tile" to "${plan.z}/${plan.x}/${plan.y}", "sampleZoom" to plan.sampleZoom, "grid" to plan.windowSize,
                "ms" to round1(totalMs), "waitMs" to round1(waitMs), "sampleMs" to round1(sampleMs),
                "fieldMs" to round1(tile.fieldMs), "composeMs" to round1(tile.composeMs),
                "encodeMs" to round1(encodeMs), "bytes" to bytes, "slopePixels" to tile.slopePixels, "options" to options.key,
            ),
        )
        while (recent.size > keepRecent) recent.removeFirst()
    }

    @Synchronized
    fun json(): Map<String, Any?> = linkedMapOf(
        "count" to count,
        "minMs" to if (count == 0L) null else round1(min),
        "avgMs" to if (count == 0L) null else round1(total / count),
        "maxMs" to if (count == 0L) null else round1(max),
        "totalMs" to round1(total),
        "byZoom" to byZoom.keys.sorted().associate { z ->
            val t = byZoom.getValue(z)
            z.toString() to linkedMapOf("count" to t.count, "minMs" to round1(t.min), "avgMs" to round1(t.total / t.count), "maxMs" to round1(t.max))
        },
        "recent" to recent.toList(),
    )

    private fun round1(d: Double) = (d * 10).roundToLong() / 10.0
}
