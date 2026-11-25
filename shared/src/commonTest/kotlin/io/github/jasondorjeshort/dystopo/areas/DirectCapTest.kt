package io.github.jasondorjeshort.dystopo.areas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T369: the public vector source's cap on one download, 3,000 vector tiles and 50 MB ([AreaLimit]). */
class DirectCapTest {
    @Test
    fun threeThousandVectorTilesIsTheLastAllowed() {
        assertFalse(AreaLimit.overDirectCap(2_999))
        assertFalse(AreaLimit.overDirectCap(3_000))
        assertTrue(AreaLimit.overDirectCap(3_001))
    }

    @Test
    fun fiftyMegabytesStopsTheDownload() {
        assertFalse(AreaLimit.directBytesReached(49_999_999))
        assertTrue(AreaLimit.directBytesReached(50_000_000))
        assertTrue(AreaLimit.directBytesReached(50_000_001))
    }

    @Test
    fun theManifestCountsItsVectorTilesApart() {
        // Rainier's public box (areas.json), elevation z8 to z15 and vector z8 to z14: only the vector tiles are the cap's.
        val area = AreaOfInterest(
            AreaShape.Polygon(listOf(listOf(AreaPoint(46.78, -121.86), AreaPoint(46.78, -121.66), AreaPoint(46.93, -121.66), AreaPoint(46.93, -121.86)))),
            8, 15, "rainier",
        )
        val items = AreaManifest.tileItems(area, 8..15, 8..14)
        val m = AreaManifest(area.id, "rainier", area, AreaManifest.STATUS_READY, items)
        val osm = items.single { it.route == AreaManifest.ROUTE_OSM }
        assertEquals(osm.count, m.vectorTileCount)
        assertEquals(osm.bytes, m.vectorBytes)
        assertTrue(m.vectorTileCount < m.tileCount, "the elevation tiles are not counted")
        assertFalse(AreaLimit.overDirectCap(m.vectorTileCount.toLong()), "Rainier's box is well inside the cap: ${m.vectorTileCount}")
        assertEquals(0, AreaManifest(area.id, "x", area, AreaManifest.STATUS_READY, emptyList()).vectorTileCount)
    }
}
