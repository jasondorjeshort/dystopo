package io.github.jasondorjeshort.dystopo.render

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Horn's 3x3 surface gradient, shared by [Hillshade] and [Slope].
 *
 * Calls [block] once per cell, row by row, with the cell index and the gradient in meters of rise
 * per meter of ground: `dzdx` toward the east, `dzdy` toward the north. Cells outside the grid
 * are clamped to the edge; neighbors with no data (NaN) are replaced by the center cell, so a
 * no-data hole only flattens the gradient next to it instead of spreading NaN. Cells that are
 * themselves NaN get NaN for both components.
 */
internal inline fun hornGradient(grid: ElevationGrid, zFactor: Double = 1.0, block: (index: Int, dzdx: Double, dzdy: Double) -> Unit) {
    val scale = zFactor / (8.0 * grid.cellSizeMeters)
    for (y in 0 until grid.height) {
        for (x in 0 until grid.width) {
            val index = y * grid.width + x
            val e = grid.values[index]
            if (e.isNaN()) {
                block(index, Double.NaN, Double.NaN)
                continue
            }
            val a = orCenter(grid.clamped(x - 1, y - 1), e)
            val b = orCenter(grid.clamped(x, y - 1), e)
            val c = orCenter(grid.clamped(x + 1, y - 1), e)
            val d = orCenter(grid.clamped(x - 1, y), e)
            val f = orCenter(grid.clamped(x + 1, y), e)
            val g = orCenter(grid.clamped(x - 1, y + 1), e)
            val h = orCenter(grid.clamped(x, y + 1), e)
            val i = orCenter(grid.clamped(x + 1, y + 1), e)
            val dzdx = ((c + 2 * f + i) - (a + 2 * d + g)) * scale
            // Rows grow southward, so negate to get the northward gradient.
            val dzdy = -((g + 2 * h + i) - (a + 2 * b + c)) * scale
            block(index, dzdx, dzdy)
        }
    }
}

internal fun orCenter(v: Float, center: Float): Double = if (v.isNaN()) center.toDouble() else v.toDouble()

/**
 * The Horn gradient of a whole grid at z-factor 1, kept so hillshade and slope can share one pass
 * over the grid when a map shows both. Same values [hornGradient] would hand out, cell for cell;
 * NaN where the grid has no data.
 */
class Gradient(val width: Int, val height: Int, val dzdx: DoubleArray, val dzdy: DoubleArray) {
    init {
        require(dzdx.size == width * height && dzdy.size == width * height) { "gradient arrays must be ${width}x$height" }
    }

    /** Calls [block] per cell like [hornGradient], with both components scaled by [zFactor]. */
    internal inline fun forEach(zFactor: Double = 1.0, block: (index: Int, dzdx: Double, dzdy: Double) -> Unit) {
        if (zFactor == 1.0) {
            for (i in dzdx.indices) block(i, dzdx[i], dzdy[i])
        } else {
            for (i in dzdx.indices) block(i, dzdx[i] * zFactor, dzdy[i] * zFactor)
        }
    }

    companion object {
        fun of(grid: ElevationGrid): Gradient {
            val n = grid.width * grid.height
            val gx = DoubleArray(n)
            val gy = DoubleArray(n)
            hornGradient(grid) { i, dzdx, dzdy ->
                gx[i] = dzdx
                gy[i] = dzdy
            }
            return Gradient(grid.width, grid.height, gx, gy)
        }
    }
}

/** One slope-angle band of a shading: cells with [minDeg] <= slope < [maxDeg] get [rgb]. */
data class SlopeBand(val label: String, val minDeg: Double, val maxDeg: Double, val rgb: Int)

/**
 * T104: what the slope shading draws. [AVALANCHE] colours the slope angles where avalanches release
 * ([Slope.avalancheBands]); [STEEPNESS] is a plain ramp, darker where steeper, with no avalanche meaning
 * ([Slope.steepnessBands]). [key] is the spelling of the `slope` map option, `--slope-shading` and shade
 * tile URLs.
 */
enum class SlopeShading(val key: String) {
    OFF("off"), AVALANCHE("avalanche"), STEEPNESS("steepness");

    /** The bands this shading tints, lowest first; empty when [OFF]. */
    val bands: List<SlopeBand>
        get() = when (this) {
            OFF -> emptyList()
            AVALANCHE -> Slope.avalancheBands
            STEEPNESS -> Slope.steepnessBands
        }

    companion object {
        /**
         * The shading named [s], or null. The `on`/`off` spellings of the single overlay before T104 (stored
         * settings, the browser viewer's `slope=on`) still parse: `on` is [AVALANCHE].
         */
        fun parse(s: String): SlopeShading? = when (s.trim().lowercase()) {
            "off", "0", "false", "no", "none" -> OFF
            "avalanche", "on", "1", "true", "yes" -> AVALANCHE
            "steepness", "steep" -> STEEPNESS
            else -> null
        }
    }
}

object Slope {
    private const val DEG = 180.0 / PI

    /**
     * T104: the avalanche slope-angle bands, keyed to the slope angles where avalanches release (sources and the
     * reasoning in docs/PLANNING.md › Slope shading): most release on 30 to 45 degree slopes, most often near 38;
     * slabs release less often on 25 to 30 degrees and above 45, where the snow sluffs often; above 50 small loose
     * avalanches run frequently, and above 60 slabs are rare. Reds inside the release window (35-45, the most
     * frequent part, reddest), yellow below it and purple to blue above it. Edges are whole degrees as listed:
     * "25-29" covers 25 <= s < 30. Before T104 the edges were 27, 30, 32, 35, 46, 51 and 60: the first moved down
     * to 25, and 30-31 and 32-34 became one band. Colours are our own.
     *
     * | band  | color   |                                    |
     * |-------|---------|------------------------------------|
     * | 25-29 | #F0E000 | yellow: below the window, uncommon |
     * | 30-34 | #F59000 | orange: release window             |
     * | 35-45 | #E02020 | red: release window, most frequent |
     * | 46-50 | #B01890 | magenta: above, slabs less likely  |
     * | 51-59 | #6030B0 | violet: sluffs often               |
     * | 60+   | #2040D0 | blue: slabs rare                   |
     */
    val avalancheBands: List<SlopeBand> = listOf(
        SlopeBand("25-29", 25.0, 30.0, 0xF0E000),
        SlopeBand("30-34", 30.0, 35.0, 0xF59000),
        SlopeBand("35-45", 35.0, 46.0, 0xE02020),
        SlopeBand("46-50", 46.0, 51.0, 0xB01890),
        SlopeBand("51-59", 51.0, 60.0, 0x6030B0),
        SlopeBand("60+", 60.0, 90.01, 0x2040D0),
    )

    /**
     * T104: the steepness ramp, ten degrees a band from 10, pale where gentle and dark where steep (sampled from
     * a perceptually ordered sequential ramp, lightest to darkest), so steeper always reads as more serious and
     * nothing in it marks an avalanche angle. Untinted below 10 degrees.
     *
     * | band  | color   |
     * |-------|---------|
     * | 10-19 | #FECF92 |
     * | 20-29 | #FE9F6D |
     * | 30-39 | #F7705C |
     * | 40-49 | #DE4968 |
     * | 50-59 | #8C2981 |
     * | 60+   | #3B0F70 |
     */
    val steepnessBands: List<SlopeBand> = listOf(
        SlopeBand("10-19", 10.0, 20.0, 0xFECF92),
        SlopeBand("20-29", 20.0, 30.0, 0xFE9F6D),
        SlopeBand("30-39", 30.0, 40.0, 0xF7705C),
        SlopeBand("40-49", 40.0, 50.0, 0xDE4968),
        SlopeBand("50-59", 50.0, 60.0, 0x8C2981),
        SlopeBand("60+", 60.0, 90.01, 0x3B0F70),
    )

    /** Slope angle in degrees (0 = flat, 90 = vertical) from a gradient. */
    fun degrees(dzdx: Double, dzdy: Double): Double = atan(sqrt(dzdx * dzdx + dzdy * dzdy)) * DEG

    /**
     * Aspect in degrees clockwise from north: the compass direction the slope faces (its downhill
     * direction). Ground rising to the east faces west, 270. NaN on flat ground.
     */
    fun aspect(dzdx: Double, dzdy: Double): Double {
        if (dzdx == 0.0 && dzdy == 0.0) return Double.NaN
        // Downhill is -gradient; compass bearing of (east, north) is atan2(east, north).
        val a = atan2(-dzdx, -dzdy) * DEG
        return if (a < 0) a + 360.0 else a
    }

    /** Slope angle in degrees per cell, same layout as [grid]; NaN where the grid has no data. */
    fun compute(grid: ElevationGrid): FloatArray {
        val out = FloatArray(grid.width * grid.height)
        hornGradient(grid) { i, dzdx, dzdy -> out[i] = degrees(dzdx, dzdy).toFloat() }
        return out
    }

    /** [compute] from a gradient already taken (see [Gradient]); identical results. */
    fun compute(gradient: Gradient): FloatArray {
        val out = FloatArray(gradient.width * gradient.height)
        gradient.forEach { i, dzdx, dzdy -> out[i] = degrees(dzdx, dzdy).toFloat() }
        return out
    }

    /** Aspect in degrees per cell (see [aspect]); NaN on flat ground and where there is no data. */
    fun computeAspect(grid: ElevationGrid): FloatArray {
        val out = FloatArray(grid.width * grid.height)
        hornGradient(grid) { i, dzdx, dzdy -> out[i] = aspect(dzdx, dzdy).toFloat() }
        return out
    }

    /** Index into [bands] for a slope angle, or -1 below the first band, for NaN, or with no bands. */
    fun bandIndex(deg: Float, bands: List<SlopeBand>): Int {
        if (bands.isEmpty() || deg.isNaN() || deg < bands[0].minDeg) return -1
        for (k in bands.indices) if (deg < bands[k].maxDeg) return k
        return bands.size - 1
    }

    /**
     * Fraction of cells with data in each band of [bands], in order. Cells below the first band
     * count toward the total but no band; NaN cells are left out entirely.
     */
    fun bandFractions(slopes: FloatArray, bands: List<SlopeBand>): DoubleArray {
        val counts = IntArray(bands.size)
        var valid = 0
        for (s in slopes) {
            if (s.isNaN()) continue
            valid++
            val k = bandIndex(s, bands)
            if (k >= 0) counts[k]++
        }
        return DoubleArray(bands.size) { if (valid == 0) 0.0 else counts[it].toDouble() / valid }
    }
}
