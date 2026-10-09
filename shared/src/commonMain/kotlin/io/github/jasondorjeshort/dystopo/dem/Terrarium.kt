package io.github.jasondorjeshort.dystopo.dem

/**
 * Terrarium elevation encoding (Mapzen/Tilezen terrain tiles): each RGB pixel stores
 * `elevation = R * 256 + G + B / 256 - 32768` meters.
 *
 * Image decoding is platform-specific, so this works on already-decoded ARGB pixels.
 */
object Terrarium {
    fun elevation(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 256 + g + b / 256f) - 32768f
    }

    fun encode(elevationMeters: Float): Int {
        val v = elevationMeters + 32768f
        val whole = v.toInt()
        val r = whole / 256
        val g = whole % 256
        val b = ((v - whole) * 256f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun decode(argb: IntArray, width: Int, height: Int, cellSizeMeters: Double): ElevationGrid {
        require(argb.size == width * height) { "expected ${width * height} pixels, got ${argb.size}" }
        return ElevationGrid(width, height, cellSizeMeters, FloatArray(argb.size) { elevation(argb[it]) })
    }
}
