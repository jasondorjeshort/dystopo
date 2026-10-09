package io.github.jasondorjeshort.dystopo.dem

/**
 * A rectangular grid of elevations in meters, row-major with row 0 at the north edge
 * (same orientation as an image). [cellSizeMeters] is the ground distance between
 * neighboring samples; for Web Mercator grids it's approximate (taken at one latitude).
 */
class ElevationGrid(
    val width: Int,
    val height: Int,
    val cellSizeMeters: Double,
    val values: FloatArray,
) {
    init {
        require(width > 0 && height > 0) { "empty grid ${width}x$height" }
        require(values.size == width * height) { "expected ${width * height} values, got ${values.size}" }
        require(cellSizeMeters > 0) { "cellSizeMeters must be positive" }
    }

    operator fun get(x: Int, y: Int): Float = values[y * width + x]

    /** Like [get], but coordinates outside the grid are clamped to the nearest edge sample. */
    fun clamped(x: Int, y: Int): Float = values[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

    fun min(): Float = values.min()

    fun max(): Float = values.max()

    fun crop(x0: Int, y0: Int, w: Int, h: Int): ElevationGrid {
        require(x0 >= 0 && y0 >= 0 && x0 + w <= width && y0 + h <= height) {
            "crop ($x0,$y0 ${w}x$h) outside grid ${width}x$height"
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            values.copyInto(out, y * w, (y0 + y) * width + x0, (y0 + y) * width + x0 + w)
        }
        return ElevationGrid(w, h, cellSizeMeters, out)
    }
}
