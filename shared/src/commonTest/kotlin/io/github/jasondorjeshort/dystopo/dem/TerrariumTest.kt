package io.github.jasondorjeshort.dystopo.dem

import kotlin.test.Test
import kotlin.test.assertEquals

class TerrariumTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun decodesKnownValues() {
        assertEquals(0f, Terrarium.elevation(rgb(128, 0, 0)))
        assertEquals(1000.5f, Terrarium.elevation(rgb(131, 232, 128)))
        assertEquals(-32768f, Terrarium.elevation(rgb(0, 0, 0)))
    }

    @Test
    fun encodeRoundTrips() {
        for (e in listOf(0f, 4392.25f, -10.5f, 8848f)) {
            assertEquals(e, Terrarium.elevation(Terrarium.encode(e)), 1f / 256)
        }
    }

    @Test
    fun decodeBuildsGrid() {
        val grid = Terrarium.decode(intArrayOf(rgb(128, 0, 0), rgb(128, 10, 0)), 2, 1, 30.0)
        assertEquals(0f, grid[0, 0])
        assertEquals(10f, grid[1, 0])
    }
}
