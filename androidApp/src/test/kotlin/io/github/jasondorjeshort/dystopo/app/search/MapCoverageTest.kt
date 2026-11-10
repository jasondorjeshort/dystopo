package io.github.jasondorjeshort.dystopo.app.search

import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.online.OnlineTiles
import io.github.jasondorjeshort.dystopo.app.routing.ROUTE_NO_MAP
import io.github.jasondorjeshort.dystopo.app.routing.ROUTE_TILES_ONLY
import io.github.jasondorjeshort.dystopo.app.routing.routeSentence
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T255: whether search and routes can answer at a place ([MapCoverage]): an offline map's vector archive covers it, only map
 * tiles draw there, or nothing; the reason codes and sentences of the search box, `GET /search` and the route card.
 */
class MapCoverageTest {
    /** A box around Mount Rainier, as a pack's vector archive covers it. */
    private val rainier = PackBounds(-121.95, 46.7, -121.55, 47.0)

    @Test
    fun aVectorArchivesBoxComesFirst() {
        assertTrue(MapCoverage.inside(listOf(rainier), 46.786, -121.735)) // Paradise
        assertTrue(MapCoverage.inside(listOf(rainier), 46.7, -121.95), "edges count")
        assertFalse(MapCoverage.inside(listOf(rainier), 45.833, 6.865)) // Mont Blanc
        assertFalse(MapCoverage.inside(emptyList(), 46.786, -121.735))
        // Inside a vector archive the view's source does not matter.
        for (s in listOf(OnlineTiles.PACK, OnlineService.ONLINE, OnlineService.CACHED, OnlineTiles.WIDER, OnlineTiles.NONE)) {
            assertEquals(MapCoverage.VECTOR, MapCoverage.of(true, s), s)
        }
    }

    @Test
    fun outsideEveryVectorArchiveTheSourceSaysTilesOrNothing() {
        // A terrain-only pack, a tile from the network, viewed earlier or kept by a map-only download, cut from a wider zoom.
        for (s in listOf(OnlineTiles.PACK, OnlineService.ONLINE, OnlineService.CACHED, OnlineTiles.WIDER)) {
            assertEquals(MapCoverage.TILES_ONLY, MapCoverage.of(false, s), s)
        }
        assertEquals(MapCoverage.NO_MAP, MapCoverage.of(false, OnlineTiles.NONE))
    }

    @Test
    fun theSearchReasonAndSentence() {
        assertEquals("found", MapCoverage.searchReason(MapCoverage.VECTOR, 3))
        assertNull(MapCoverage.searchSentence(MapCoverage.VECTOR, 3))
        assertEquals("no-match", MapCoverage.searchReason(MapCoverage.VECTOR, 0))
        assertEquals("No places match", MapCoverage.searchSentence(MapCoverage.VECTOR, 0))
        // Hits of a map elsewhere do not hide why nothing here is searched.
        assertEquals("tiles-only", MapCoverage.searchReason(MapCoverage.TILES_ONLY, 2))
        assertEquals("Search needs an offline map here; this area has map tiles only", MapCoverage.searchSentence(MapCoverage.TILES_ONLY, 2))
        assertEquals("no-map", MapCoverage.searchReason(MapCoverage.NO_MAP, 0))
        assertEquals("No map data here to search", MapCoverage.searchSentence(MapCoverage.NO_MAP, 0))
    }

    @Test
    fun aRouteNeedsAVectorArchiveAtBothEnds() {
        assertEquals(MapCoverage.VECTOR, MapCoverage.ofRoute(MapCoverage.VECTOR, MapCoverage.VECTOR))
        assertEquals(MapCoverage.TILES_ONLY, MapCoverage.ofRoute(MapCoverage.VECTOR, MapCoverage.TILES_ONLY))
        assertEquals(MapCoverage.TILES_ONLY, MapCoverage.ofRoute(MapCoverage.NO_MAP, MapCoverage.TILES_ONLY))
        assertEquals(MapCoverage.NO_MAP, MapCoverage.ofRoute(MapCoverage.NO_MAP, MapCoverage.VECTOR))
        assertEquals("Routes need an offline map here; this area has map tiles only", routeSentence(MapCoverage.TILES_ONLY))
        assertEquals(ROUTE_TILES_ONLY, routeSentence(MapCoverage.TILES_ONLY))
        assertEquals(ROUTE_NO_MAP, routeSentence(MapCoverage.NO_MAP))
        assertEquals(listOf("vector", "tiles-only", "no-map"), MapCoverage.entries.map { it.code })
    }
}
