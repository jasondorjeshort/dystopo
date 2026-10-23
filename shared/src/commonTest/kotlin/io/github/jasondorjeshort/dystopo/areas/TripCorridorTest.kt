package io.github.jasondorjeshort.dystopo.areas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** T130: the trip corridor's manifest at the 1 km default against T75's 500 m. */
class TripCorridorTest {
    // Paradise to Camp Muir and back down a side line (public), about 7 km with bends.
    private val route = listOf(
        AreaPoint(46.7859, -121.7356), AreaPoint(46.7930, -121.7340), AreaPoint(46.8016, -121.7355), AreaPoint(46.8037, -121.7268),
        AreaPoint(46.8150, -121.7310), AreaPoint(46.8255, -121.7260), AreaPoint(46.8352, -121.7325),
    )

    private fun manifest(a: AreaOfInterest) = AreaManifest(a.id, a.name ?: a.id, a, AreaManifest.STATUS_READY, AreaManifest.tileItems(a, 6..a.maxZoom.coerceAtMost(15), a.minZoom..minOf(a.maxZoom, 14)))

    @Test
    fun oneKilometreHoldsTheFiveHundredMetreCorridorAndMore() {
        val t75 = TripCorridor.area(route, "hike", 500.0, "Muir")
        val km = TripCorridor.area(route, "hike", TripCorridor.DEFAULT_WIDTH_M.toDouble(), "Muir")
        // The width grows by the simplification tolerance (a tenth), so the corridor still holds the whole line.
        assertEquals(550.0, (t75.shape as AreaShape.Corridor).bufferM)
        assertEquals(1100.0, (km.shape as AreaShape.Corridor).bufferM)
        assertEquals(8 to 15, km.minZoom to km.maxZoom)
        assertEquals(1000, TripCorridor.widthOf(1100.0))
        assertEquals(500, TripCorridor.widthOf(550.0))
        assertNotEquals(t75.id, km.id, "a width change is a new area")
        val a = manifest(t75)
        val b = manifest(km)
        for (route in listOf(AreaManifest.ROUTE_DEM, AreaManifest.ROUTE_OSM)) {
            val small = a.tiles(route)!!
            val big = b.tiles(route)!!
            assertEquals(small.tiles.keys, big.tiles.keys)
            for ((z, set) in small.tiles) {
                val wide = big.tiles.getValue(z)
                for ((x, y) in set.tiles()) assertTrue(wide.contains(x, y), "$route z$z: $x/$y of the 500 m corridor not in the 1 km one")
            }
            // At the deepest zoom the 1 km corridor is no smaller (68 against 53 DEM tiles at z15 here; 28 and 28 vector tiles at z14,
            // where the one-tile margin covers both) and under three times (it is a band, not a box).
            val z = small.tiles.keys.max()
            val ratio = big.tiles.getValue(z).size.toDouble() / small.tiles.getValue(z).size
            assertTrue(ratio in 1.0..3.0, "$route z$z: ${big.tiles.getValue(z).size} vs ${small.tiles.getValue(z).size} tiles")
        }
        assertTrue(b.tiles(AreaManifest.ROUTE_DEM)!!.tiles.getValue(15).size > a.tiles(AreaManifest.ROUTE_DEM)!!.tiles.getValue(15).size)
        assertTrue(b.bytes > a.bytes && b.tileCount > a.tileCount)
    }

    @Test
    fun aDriveStopsAtZoomThirteenAndTheSameRouteIsTheSameArea() {
        val drive = TripCorridor.area(route, "drive", 1000.0, "Drive")
        assertEquals(13, drive.maxZoom)
        assertEquals(15, TripCorridor.maxZoom("bike"))
        assertEquals(15, TripCorridor.maxZoom(null))
        assertEquals(drive.id, TripCorridor.area(route, "Drive", 1000.0, "another name").id)
        assertEquals(drive.id, TripCorridor.area(route, "drive", 1000.0, null, maxZoom = 13).id)
    }

    @Test
    fun widths() {
        assertTrue(TripCorridor.validWidth(0))
        assertTrue(TripCorridor.validWidth(1000))
        assertFalse(TripCorridor.validWidth(50))
        assertFalse(TripCorridor.validWidth(20_000))
        assertTrue(TripCorridor.WIDTH_CHOICES_M.all { TripCorridor.validWidth(it) })
        assertTrue(TripCorridor.DEFAULT_WIDTH_M in TripCorridor.WIDTH_CHOICES_M)
    }
}
