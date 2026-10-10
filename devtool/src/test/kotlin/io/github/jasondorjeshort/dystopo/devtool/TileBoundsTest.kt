package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import kotlin.test.Test
import kotlin.test.assertEquals

class TileBoundsTest {
    @Test
    fun anOrdinaryBoxIsUnchanged() {
        val box = BoundingBox(-121.86, 46.78, -121.66, 46.93)
        assertEquals(listOf(-121.86, 46.78, -121.66, 46.93), TilesCommand.geoJsonBounds(box))
        assertEquals(box.centerLon, TilesCommand.centerLon(box), 1e-12)
    }

    @Test
    fun anAntimeridianBoxIsWrittenWestGreaterThanEast() {
        val box = BoundingBox.unwrapped(179.88, -16.92, -179.88, -16.76)
        assertEquals(180.12, box.east, 1e-9) // internally unwrapped past 180
        val (w, s, e, n) = TilesCommand.geoJsonBounds(box)
        assertEquals(179.88, w, 1e-9)
        assertEquals(-179.88, e, 1e-9)
        assertEquals(-16.92, s)
        assertEquals(-16.76, n)
        assertEquals(180.0, TilesCommand.centerLon(box), 1e-9)
        // Center past 180 wraps too.
        assertEquals(-179.95, TilesCommand.centerLon(BoundingBox.unwrapped(179.9, 0.0, -179.8, 1.0)), 1e-9)
    }

    @Test
    fun aBoxEndingAt180Keeps180() {
        assertEquals(180.0, TilesCommand.geoJsonBounds(BoundingBox(179.5, 0.0, 180.0, 1.0))[2])
    }
}
