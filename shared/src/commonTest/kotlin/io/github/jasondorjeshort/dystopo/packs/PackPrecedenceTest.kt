package io.github.jasondorjeshort.dystopo.packs

import io.github.jasondorjeshort.dystopo.geo.WebMercator
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals

class PackPrecedenceTest {
    private val rainier = PackBounds(-122.05, 46.6, -121.4, 47.1)
    private val paradise = PackBounds(-121.80, 46.75, -121.65, 46.83)

    private fun tile(lat: Double, lon: Double, z: Int) =
        Triple(z, floor(WebMercator.lonToX(lon, z)).toInt(), floor(WebMercator.latToY(lat, z)).toInt())

    @Test
    fun profileFromMetadata() {
        assertEquals(VectorProfile.OUTDOORS, PackPrecedence.vectorProfile(mapOf("dystopo:profile" to "outdoors")))
        assertEquals(VectorProfile.OPENMAPTILES, PackPrecedence.vectorProfile(mapOf("dystopo:profile" to "openmaptiles")))
        // tilebuild's outdoors schema names OpenMapTiles too; the outdoors prefix decides.
        assertEquals(VectorProfile.OUTDOORS, PackPrecedence.vectorProfile(mapOf("dystopo:schema" to "Dystopo outdoors 1 (OpenMapTiles 3.16 layer names)")))
        assertEquals(VectorProfile.OPENMAPTILES, PackPrecedence.vectorProfile(mapOf("dystopo:schema" to "OpenMapTiles 3.16")))
        assertEquals(VectorProfile.OPENMAPTILES, PackPrecedence.vectorProfile(mapOf("dystopo:provenance" to "osm,openfreemap")))
        assertEquals(VectorProfile.UNKNOWN, PackPrecedence.vectorProfile(emptyMap()))
        assertEquals(true, PackPrecedence.wholeTiles(mapOf("dystopo:provenance" to "osm,openfreemap,openmaptiles")))
    }

    @Test
    fun outdoorsBeatsOpenMapTilesWhereBothHoldTheTile() {
        // A plain OpenMapTiles pack that is deeper, primary and smaller still loses to an outdoors pack.
        val omt = PackCoverage("view", 0, 14, paradise, VectorProfile.OPENMAPTILES, primary = true, wholeTiles = true)
        val outdoors = PackCoverage("rainier", 0, 14, rainier, VectorProfile.OUTDOORS)
        val sorted = PackPrecedence.sort(listOf(omt, outdoors)) { it }
        assertEquals(listOf("rainier", "view"), sorted.map { it.pack })
        val (z, x, y) = tile(46.786, -121.735, 14)
        assertEquals(0, PackPrecedence.choose(sorted, z, x, y, PackRoleZooms.VECTOR))
        // Outside the outdoors pack only the other one answers; outside both, nobody.
        val far = PackCoverage("far", 0, 14, PackBounds(6.7, 45.7, 7.0, 46.0), VectorProfile.OPENMAPTILES)
        val all = PackPrecedence.sort(listOf(omt, outdoors, far)) { it }
        val (zm, xm, ym) = tile(45.83, 6.86, 13)
        assertEquals("far", all[PackPrecedence.choose(all, zm, xm, ym, PackRoleZooms.VECTOR)].pack)
        val (zn, xn, yn) = tile(0.0, 0.0, 10)
        assertEquals(-1, PackPrecedence.choose(all, zn, xn, yn, PackRoleZooms.VECTOR))
    }

    @Test
    fun anEdgeTileGoesToThePackThatHoldsAllOfIt() {
        // The small outdoors pack ranks first, but a tile across its edge is clipped there; the large pack has it whole.
        val small = PackCoverage("small", 0, 14, paradise, VectorProfile.OUTDOORS)
        val large = PackCoverage("large", 0, 14, rainier, VectorProfile.OPENMAPTILES)
        val sorted = PackPrecedence.sort(listOf(large, small)) { it }
        assertEquals("small", sorted.first().pack)
        val (z, x, y) = tile(46.83, -121.725, 13) // straddles the small pack's north edge
        assertEquals("large", sorted[PackPrecedence.choose(sorted, z, x, y, PackRoleZooms.VECTOR)].pack)
        assertEquals(listOf("large", "small"), PackPrecedence.candidates(sorted, z, x, y, PackRoleZooms.VECTOR).map { sorted[it].pack })
    }

    @Test
    fun zoomRulesPerRole() {
        val dem = PackCoverage("rainier", 8, 14, rainier)
        val (_, x16, y16) = tile(46.85, -121.76, 16)
        // DEM tiles past the pack's max are synthesized from ancestors; vector tiles past it are not the pack's.
        assertEquals(0, PackPrecedence.choose(listOf(dem), 16, x16, y16, PackRoleZooms.DEM))
        assertEquals(-1, PackPrecedence.choose(listOf(dem), 16, x16, y16, PackRoleZooms.VECTOR))
        val (_, x7, y7) = tile(46.85, -121.76, 7)
        assertEquals(-1, PackPrecedence.choose(listOf(dem), 7, x7, y7, PackRoleZooms.DEM))
        assertEquals(0, PackPrecedence.choose(listOf(dem), 7, x7, y7, PackRoleZooms.CONTOUR))
    }

    @Test
    fun deeperThenPrimaryThenSmallerThenName() {
        val a = PackCoverage("a", 8, 13, rainier)
        val b = PackCoverage("b", 8, 14, rainier)
        val c = PackCoverage("c", 8, 14, rainier, primary = true)
        val d = PackCoverage("d", 8, 14, paradise)
        assertEquals(listOf("c", "d", "b", "a"), PackPrecedence.sort(listOf(a, b, c, d)) { it }.map { it.pack })
    }
}
