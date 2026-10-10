package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.render.Hillshade
import io.github.jasondorjeshort.dystopo.render.Slope
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class ShadingStats(
    /** hillshade, multi, or none. */
    val mode: String,
    val slopeOverlay: Boolean,
    val slopeOpacity: Double? = null,
    /** Fraction of cells with data in each slope band (band label -> fraction); only with the overlay. */
    val slopeBandFractions: Map<String, Double>? = null,
)

/**
 * The base layer under contours and labels: warm paper tone darkened by the hillshade, with the
 * slope-angle bands composited on top as translucent tints.
 */
object Shading {
    private val paper = intArrayOf(246, 241, 228)
    private const val NO_DATA = (200 shl 16) or (60 shl 8) or 200 // magenta: impossible to miss

    /** Hillshade brightness b in 0..1 maps to paper × (FLOOR + (1 - FLOOR) × b). */
    private const val FLOOR = 0.35

    /** Returns RGB pixels, row-major like [grid], and stats for the report. */
    fun render(grid: ElevationGrid, style: StyleOptions, timings: Timings, prefix: String = ""): Pair<IntArray, ShadingStats> {
        val shade = when (style.shading) {
            "hillshade" -> timings.time("${prefix}hillshade") { Hillshade.compute(grid, zFactor = style.zFactor) }
            "multi" -> timings.time("${prefix}hillshade") { Hillshade.multidirectional(grid, zFactor = style.zFactor) }
            else -> null
        }
        // Slope is always the true angle (no z-factor): the bands mean real degrees.
        val slope = if (style.slopeOverlay) timings.time("${prefix}slope") { Slope.compute(grid) } else null
        val opacity = style.slopeOpacity.coerceIn(0.0, 1.0)

        val pixels = timings.time("${prefix}shading") { compose(grid, shade, slope, opacity) }
        val stats = ShadingStats(
            mode = style.shading,
            slopeOverlay = slope != null,
            slopeOpacity = if (slope != null) opacity else null,
            slopeBandFractions = slope?.let { s ->
                val fractions = Slope.bandFractions(s)
                Slope.bands.withIndex().associate { (k, band) -> band.label to (fractions[k] * 10000).roundToInt() / 10000.0 }
            },
        )
        return pixels to stats
    }

    private fun compose(grid: ElevationGrid, shade: FloatArray?, slope: FloatArray?, opacity: Double): IntArray {
        val tints = Slope.bands.map { band ->
            intArrayOf((band.rgb shr 16) and 0xFF, (band.rgb shr 8) and 0xFF, band.rgb and 0xFF)
        }
        val keep = 1.0 - opacity
        return IntArray(grid.width * grid.height) { i ->
            if (grid.values[i].isNaN()) return@IntArray NO_DATA
            val k = if (shade == null) 1.0 else FLOOR + (1.0 - FLOOR) * shade[i]
            var r = paper[0] * k
            var g = paper[1] * k
            var b = paper[2] * k
            val band = if (slope == null) -1 else Slope.bandIndex(slope[i])
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
