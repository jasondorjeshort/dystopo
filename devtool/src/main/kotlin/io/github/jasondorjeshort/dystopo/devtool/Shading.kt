package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.dem.Smooth
import io.github.jasondorjeshort.dystopo.render.Gradient
import io.github.jasondorjeshort.dystopo.render.Hillshade
import io.github.jasondorjeshort.dystopo.render.Slope
import io.github.jasondorjeshort.dystopo.render.SlopeBand
import io.github.jasondorjeshort.dystopo.render.SlopeShading
import io.github.jasondorjeshort.dystopo.tiles.ShadeTiles
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class ShadingStats(
    /** hillshade, multi, or none. */
    val mode: String,
    /** T104: the slope shading drawn: off, avalanche or steepness (`--slope-shading`). */
    val slopeShading: String,
    val slopeOpacity: Double? = null,
    /** Radius of the smoothing applied to the grid before hillshade and slope (`--smooth-dem`); null when off. */
    val smoothDemRadius: Int? = null,
    /** Fraction of cells with data in each band of the slope shading (band label -> fraction); only with a shading. */
    val slopeBandFractions: Map<String, Double>? = null,
)

/**
 * The base layer under contours and labels: warm paper tone darkened by the hillshade, with the bands
 * of the slope shading ([SlopeShading]: avalanche slope angles or steepness) composited on top as
 * translucent tints.
 */
object Shading {
    private val paper = intArrayOf(246, 241, 228)
    private const val NO_DATA = (200 shl 16) or (60 shl 8) or 200 // magenta: impossible to miss

    /** Hillshade brightness b in 0..1 maps to paper × (FLOOR + (1 - FLOOR) × b). */
    private const val FLOOR = 0.35

    /** Returns RGB pixels, row-major like [grid], and stats for the report. */
    fun render(grid: ElevationGrid, style: StyleOptions, timings: Timings, prefix: String = ""): Pair<IntArray, ShadingStats> {
        val wantShade = style.shading != "none"
        val bands = style.slopeShading.bands
        val wantSlope = bands.isNotEmpty()
        // Optional light smoothing for shading only; contours are traced elsewhere, on the raw grid.
        val surface = if (style.smoothDem > 0 && (wantShade || wantSlope)) {
            timings.time("${prefix}smoothDem") { Smooth.gaussian(grid, style.smoothDem) }
        } else grid
        // With both layers on, take the Horn gradient once and derive both from it.
        val gradient = if (wantShade && wantSlope) timings.time("${prefix}gradient") { Gradient.of(surface) } else null
        val shade = when (style.shading) {
            "hillshade" -> timings.time("${prefix}hillshade") {
                if (gradient != null) Hillshade.compute(gradient, zFactor = style.zFactor) else Hillshade.compute(surface, zFactor = style.zFactor)
            }
            "multi" -> timings.time("${prefix}hillshade") {
                if (gradient != null) Hillshade.multidirectional(gradient, zFactor = style.zFactor)
                else Hillshade.multidirectional(surface, zFactor = style.zFactor)
            }
            else -> null
        }
        // Slope is always the true angle (no z-factor): the bands mean real degrees.
        val slope = if (wantSlope) {
            timings.time("${prefix}slope") { if (gradient != null) Slope.compute(gradient) else Slope.compute(surface) }
        } else null
        val opacity = style.slopeOpacity.coerceIn(0.0, 1.0)

        val pixels = timings.time("${prefix}shading") { compose(grid, shade, slope, bands, opacity) }
        val stats = ShadingStats(
            mode = style.shading,
            slopeShading = style.slopeShading.key,
            slopeOpacity = if (slope != null) opacity else null,
            smoothDemRadius = if (surface !== grid) style.smoothDem else null,
            slopeBandFractions = slope?.let { s ->
                val fractions = Slope.bandFractions(s, bands)
                bands.withIndex().associate { (k, band) -> band.label to (fractions[k] * 10000).roundToInt() / 10000.0 }
            },
        )
        return pixels to stats
    }

    /**
     * T181: the sun-and-shade picture of `devtool shade` and its goldens: the paper tone darkened by the
     * multidirectional hillshade (as the base map), with the terrain's shade ([shade]: 1 in shade, 0 in sun, NaN without
     * data, the layout of [grid]) laid over it in the shade tiles' tint ([ShadeTiles.SUN_SHADE_RGB] at [opacity], shade
     * only, sunlit ground untouched), exactly as the phone composes a shade tile over its map. Magenta where [grid]
     * has no data.
     */
    fun sunShade(grid: ElevationGrid, shade: FloatArray, opacity: Double = ShadeTiles.SUN_SHADE_OPACITY, zFactor: Double = 1.0): IntArray {
        require(shade.size == grid.values.size) { "shade ${shade.size} cells, grid ${grid.values.size}" }
        val light = Hillshade.multidirectional(grid, zFactor = zFactor)
        val tr = (ShadeTiles.SUN_SHADE_RGB shr 16) and 0xFF
        val tg = (ShadeTiles.SUN_SHADE_RGB shr 8) and 0xFF
        val tb = ShadeTiles.SUN_SHADE_RGB and 0xFF
        return IntArray(grid.values.size) { i ->
            if (grid.values[i].isNaN()) return@IntArray NO_DATA
            val k = FLOOR + (1.0 - FLOOR) * light[i]
            var r = paper[0] * k
            var g = paper[1] * k
            var b = paper[2] * k
            val a = opacity * (shade[i].takeUnless { it.isNaN() } ?: 0f)
            r = r * (1 - a) + tr * a
            g = g * (1 - a) + tg * a
            b = b * (1 - a) + tb * a
            (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)
        }
    }

    private fun compose(grid: ElevationGrid, shade: FloatArray?, slope: FloatArray?, bands: List<SlopeBand>, opacity: Double): IntArray {
        val tints = bands.map { band ->
            intArrayOf((band.rgb shr 16) and 0xFF, (band.rgb shr 8) and 0xFF, band.rgb and 0xFF)
        }
        val keep = 1.0 - opacity
        return IntArray(grid.width * grid.height) { i ->
            if (grid.values[i].isNaN()) return@IntArray NO_DATA
            val k = if (shade == null) 1.0 else FLOOR + (1.0 - FLOOR) * shade[i]
            var r = paper[0] * k
            var g = paper[1] * k
            var b = paper[2] * k
            val band = if (slope == null) -1 else Slope.bandIndex(slope[i], bands)
            if (band >= 0) {
                // Plain alpha blend ("over"): the hillshade stays visible through the tint.
                val t = tints[band]
                r = r * keep + t[0] * opacity
                g = g * keep + t[1] * opacity
                b = b * keep + t[2] * opacity
            }
            (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
        }
    }
}
