package io.github.jasondorjeshort.dystopo.render

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HillshadeTest {
    private fun grid(w: Int, h: Int, f: (Int, Int) -> Double) =
        ElevationGrid(w, h, 10.0, FloatArray(w * h) { f(it % w, it / w).toFloat() })

    @Test
    fun flatGroundIsSinAltitude() {
        val shade = Hillshade.compute(grid(5, 5) { _, _ -> 100.0 }, altitudeDeg = 30.0)
        shade.forEach { assertEquals(sin(30.0 * PI / 180).toFloat(), it, 1e-6f) }
    }

    @Test
    fun slopeFacingTheLightIsBrighter() {
        // Ground rising to the east faces west.
        val risingEast = grid(5, 5) { x, _ -> x * 10.0 }
        val litFromWest = Hillshade.compute(risingEast, azimuthDeg = 270.0)[12]
        val litFromEast = Hillshade.compute(risingEast, azimuthDeg = 90.0)[12]
        val flat = sin(45.0 * PI / 180).toFloat()
        assertTrue(litFromWest > flat, "west light on west-facing slope: $litFromWest")
        assertTrue(litFromEast < flat, "east light on west-facing slope: $litFromEast")
    }

    @Test
    fun northIsUpInTheGrid() {
        // Row 0 is north, so ground rising with y rises to the south and faces north.
        val facesNorth = grid(5, 5) { _, y -> y * 10.0 }
        assertTrue(Hillshade.compute(facesNorth, azimuthDeg = 0.0)[12] > Hillshade.compute(facesNorth, azimuthDeg = 180.0)[12])
    }
}
