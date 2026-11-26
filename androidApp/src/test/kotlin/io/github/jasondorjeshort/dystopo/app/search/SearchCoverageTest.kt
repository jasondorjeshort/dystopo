package io.github.jasondorjeshort.dystopo.app.search

import io.github.jasondorjeshort.dystopo.app.routing.ROUTE_TILES_ONLY
import io.github.jasondorjeshort.dystopo.app.routing.routeSentence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * T377: search has a coverage of its own. Inside an area kept as tiles (a downloaded area with no pack) search reads the
 * area's index, so it is searchable, while a route there still reads the shared [MapCoverage] and keeps saying that routes
 * need an offline map.
 */
class SearchCoverageTest {
    @Test
    fun searchAndTheRouteDifferInsideAnAreaKeptAsTiles() {
        // The chip's source inside such an area: its kept tiles.
        val route = MapCoverage.of(false, io.github.jasondorjeshort.dystopo.app.online.OnlineService.CACHED)
        assertEquals(MapCoverage.TILES_ONLY, route)
        val search = MapCoverage.forSearch(route, areaCovers = true)
        assertEquals(MapCoverage.VECTOR, search)
        assertEquals("found", MapCoverage.searchReason(search, 3))
        assertNull(MapCoverage.searchSentence(search, 3))
        // The route's answer is unchanged: both ends inside the area, the card's line and reason as before.
        assertEquals(MapCoverage.TILES_ONLY, MapCoverage.ofRoute(route, route))
        assertEquals("tiles-only", MapCoverage.ofRoute(route, route).code)
        assertEquals(ROUTE_TILES_ONLY, routeSentence(MapCoverage.ofRoute(route, route)))
    }

    @Test
    fun outsideAnAreaSearchReadsAsTheRouteDoes() {
        for (c in MapCoverage.entries) assertEquals(c, MapCoverage.forSearch(c, areaCovers = false))
        assertEquals(MapCoverage.VECTOR, MapCoverage.forSearch(MapCoverage.NO_MAP, areaCovers = true))
        assertEquals(SEARCH_TILES_ONLY, MapCoverage.searchSentence(MapCoverage.TILES_ONLY, 0))
    }

    @Test
    fun whileTheAreaIsIndexed() {
        assertEquals("indexing", MapCoverage.searchReason(MapCoverage.VECTOR, 0, indexing = true))
        assertEquals("found", MapCoverage.searchReason(MapCoverage.VECTOR, 2, indexing = true))
        assertEquals("tiles-only", MapCoverage.searchReason(MapCoverage.TILES_ONLY, 0, indexing = true))
        assertEquals("Indexing places in Paradise 40%", areaIndexingLine("Paradise", 40, 100))
        assertEquals("Indexing places in Paradise...", areaIndexingLine("Paradise", 0, 0))
    }

    @Test
    fun anAreasIndexIsWeighedByItsTiles() {
        assertEquals(PlaceSearch.estimateBytes(0), PlaceSearch.estimateAreaBytes(0))
        assertEquals(PlaceSearch.estimateBytes(100L * PlaceSearch.AREA_BYTES_PER_TILE), PlaceSearch.estimateAreaBytes(100))
    }
}
