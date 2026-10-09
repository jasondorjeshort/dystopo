package io.github.jasondorjeshort.dystopo.render

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Hillshade {
    /**
     * Lambertian hillshade using Horn's 3x3 gradient.
     *
     * @param azimuthDeg direction the light comes from, clockwise from north (315 = north-west)
     * @param altitudeDeg light angle above the horizon
     * @param zFactor vertical exaggeration
     * @return brightness per cell in 0..1, same layout as [grid]. Flat ground gets sin(altitude).
     */
    fun compute(
        grid: ElevationGrid,
        azimuthDeg: Double = 315.0,
        altitudeDeg: Double = 45.0,
        zFactor: Double = 1.0,
    ): FloatArray {
        val az = azimuthDeg * PI / 180.0
        val alt = altitudeDeg * PI / 180.0
        // Light vector in (east, north, up).
        val lx = sin(az) * cos(alt)
        val ly = cos(az) * cos(alt)
        val lz = sin(alt)
        val scale = zFactor / (8.0 * grid.cellSizeMeters)

        val out = FloatArray(grid.width * grid.height)
        for (y in 0 until grid.height) {
            for (x in 0 until grid.width) {
                val a = grid.clamped(x - 1, y - 1)
                val b = grid.clamped(x, y - 1)
                val c = grid.clamped(x + 1, y - 1)
                val d = grid.clamped(x - 1, y)
                val f = grid.clamped(x + 1, y)
                val g = grid.clamped(x - 1, y + 1)
                val h = grid.clamped(x, y + 1)
                val i = grid.clamped(x + 1, y + 1)
                val dzdx = ((c + 2 * f + i) - (a + 2 * d + g)) * scale
                // Rows grow southward, so negate to get the northward gradient.
                val dzdy = -((g + 2 * h + i) - (a + 2 * b + c)) * scale
                // Surface normal (-dzdx, -dzdy, 1), normalized, dotted with the light.
                val shade = (-dzdx * lx - dzdy * ly + lz) / sqrt(dzdx * dzdx + dzdy * dzdy + 1.0)
                out[y * grid.width + x] = shade.coerceAtLeast(0.0).toFloat()
            }
        }
        return out
    }
}
