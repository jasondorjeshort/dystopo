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

/** One slope-angle band of the overlay: cells with [minDeg] <= slope < [maxDeg] get [rgb]. */
data class SlopeBand(val label: String, val minDeg: Double, val maxDeg: Double, val rgb: Int)

object Slope {
    private const val DEG = 180.0 / PI

    /**
     * Slope-angle bands in the style avalanche maps (CalTopo, Gaia, FATMAP) use: no tint below 27
     * degrees, then yellow through orange and red (35-45, the most avalanche-prone range) to purple
     * and blue for cliffs. Band edges are whole degrees as listed: "27-29" covers 27 <= s < 30.
     * The colors are our own choice, picked to read like the familiar CalTopo progression:
     *
     * | band  | color   |                 |
     * |-------|---------|-----------------|
     * | 27-29 | #F0E000 | yellow          |
     * | 30-31 | #F8B000 | amber           |
     * | 32-34 | #F07800 | orange          |
     * | 35-45 | #E02020 | red             |
     * | 46-50 | #B01890 | magenta-purple  |
     * | 51-59 | #6030B0 | violet          |
     * | 60+   | #2040D0 | blue            |
     */
    val bands: List<SlopeBand> = listOf(
        SlopeBand("27-29", 27.0, 30.0, 0xF0E000),
        SlopeBand("30-31", 30.0, 32.0, 0xF8B000),
        SlopeBand("32-34", 32.0, 35.0, 0xF07800),
        SlopeBand("35-45", 35.0, 46.0, 0xE02020),
        SlopeBand("46-50", 46.0, 51.0, 0xB01890),
        SlopeBand("51-59", 51.0, 60.0, 0x6030B0),
        SlopeBand("60+", 60.0, 90.01, 0x2040D0),
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

    /** Index into [bands] for a slope angle, or -1 below the first band or for NaN. */
    fun bandIndex(deg: Float): Int {
        if (deg.isNaN() || deg < bands[0].minDeg) return -1
        for (k in bands.indices) if (deg < bands[k].maxDeg) return k
        return bands.size - 1
    }

    /**
     * Fraction of cells with data in each band of [bands], in order. Cells below the first band
     * count toward the total but no band; NaN cells are left out entirely.
     */
    fun bandFractions(slopes: FloatArray): DoubleArray {
        val counts = IntArray(bands.size)
        var valid = 0
        for (s in slopes) {
            if (s.isNaN()) continue
            valid++
            val k = bandIndex(s)
            if (k >= 0) counts[k]++
        }
        return DoubleArray(bands.size) { if (valid == 0) 0.0 else counts[it].toDouble() / valid }
    }
}
