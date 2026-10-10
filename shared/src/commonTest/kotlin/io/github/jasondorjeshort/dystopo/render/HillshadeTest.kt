package io.github.jasondorjeshort.dystopo.render

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
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

    @Test
    fun multidirectionalFlatGroundIsSinAltitude() {
        val shade = Hillshade.multidirectional(grid(5, 5) { _, _ -> 100.0 }, altitudeDeg = 30.0)
        shade.forEach { assertEquals(sin(30.0 * PI / 180).toFloat(), it, 1e-6f) }
    }

    @Test
    fun multidirectionalKeepsFacesAwayFromNorthwestLit() {
        // A 50-degree face rising to the north-west faces south-east, away from the default light.
        val rise = tan(50.0 * PI / 180) * 10.0 / sqrt(2.0)
        val facesSoutheast = grid(5, 5) { x, y -> (-x - y) * rise } // rows grow south
        val single = Hillshade.compute(facesSoutheast)[12]
        val multi = Hillshade.multidirectional(facesSoutheast)[12]
        assertEquals(0f, single, 1e-6f)
        assertTrue(multi > 0.12f, "multidirectional shade on a face away from the main light: $multi")
        // Still one dominant light: the opposite face, toward the north-west, is brighter.
        val facesNorthwest = grid(5, 5) { x, y -> (x + y) * rise }
        assertTrue(Hillshade.multidirectional(facesNorthwest)[12] > multi + 0.3f)
    }

    @Test
    fun multidirectionalStaysInRange() {
        val bumpy = grid(16, 16) { x, y -> 300 * sin(x * 0.9) * cos(y * 1.3) }
        Hillshade.multidirectional(bumpy).forEach { assertTrue(it in 0f..1f, "shade $it") }
    }

    @Test
    fun noDataIsNaNAndDoesNotSpread() {
        val g = grid(5, 5) { x, _ -> x * 10.0 }
        g.values[12] = Float.NaN
        for (shade in listOf(Hillshade.compute(g), Hillshade.multidirectional(g))) {
            assertTrue(shade[12].isNaN())
            assertTrue(shade.withIndex().filter { it.index != 12 }.none { it.value.isNaN() })
        }
    }
}
