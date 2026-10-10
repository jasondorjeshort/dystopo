package io.github.jasondorjeshort.dystopo.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiPackSearchTest {
    // Public places: Rainier and Mont Blanc packs, plus a small pack overlapping Rainier's.
    private val paradise = SearchEntry("Paradise", null, PlaceKind.HAMLET, -121.7353, 46.7865, 55, 12, null)
    private val rainierPeak = SearchEntry("Mount Rainier", "Tahoma", PlaceKind.VOLCANO, -121.7603, 46.8529, 109, 10, 4392)
    private val montBlanc = SearchEntry("Mont Blanc", null, PlaceKind.PEAK, 6.8652, 45.8326, 109, 10, 4806)
    private val montBlancDuTacul = SearchEntry("Mont Blanc du Tacul", null, PlaceKind.PEAK, 6.8875, 45.8564, 90, 12, 4248)
    private val chamonix = SearchEntry("Chamonix", null, PlaceKind.TOWN, 6.8696, 45.9237, 90, 10, null)

    private fun index(name: String, vararg e: SearchEntry) =
        name to SearchIndex.of(e.toList(), SearchSource("$name-outdoors.pmtiles", "v1:$name", listOf(10, 12, 14), 0L))

    private val rainier = index("rainier", paradise, rainierPeak)
    private val paradiseBox = index("paradise", paradise.copy(lon = -121.7356, lat = 46.7867))
    private val montblanc = index("montblanc", montBlanc, montBlancDuTacul, chamonix)

    @Test
    fun findsPlacesInEveryPack() {
        val hits = MultiPackSearch.search(listOf(rainier, montblanc), "mo", lat = 46.85, lon = -121.76)
        assertEquals(setOf("rainier", "montblanc"), hits.map { it.pack }.toSet())
        assertTrue(hits.any { it.hit.entry.name == "Mont Blanc" })
        // The camera is at Rainier: Mount Rainier ranks first.
        assertEquals("Mount Rainier", hits.first().hit.entry.name)
        val fromChamonix = MultiPackSearch.search(listOf(rainier, montblanc), "mo", lat = 45.92, lon = 6.87)
        assertEquals("montblanc", fromChamonix.first().pack)
    }

    @Test
    fun overlappingPacksListAPlaceOnce() {
        val hits = MultiPackSearch.search(listOf(rainier, paradiseBox), "paradise", lat = 46.78, lon = -121.73)
        assertEquals(1, hits.count { it.hit.entry.name == "Paradise" })
    }

    @Test
    fun fuzzyHitsOnlyWhenNoPackMatchesAsTyped() {
        // "chamonix" matches as typed in Mont Blanc; a pack with a one-edit near miss must not add it.
        val near = index("near", SearchEntry("Chamonia", null, PlaceKind.HAMLET, -121.7, 46.8, 40, 12, null))
        val hits = MultiPackSearch.search(listOf(near, montblanc), "chamonix")
        assertEquals(listOf("Chamonix"), hits.map { it.hit.entry.name })
        // With no exact match anywhere, the typo fallback still works across packs.
        val typo = MultiPackSearch.search(listOf(rainier, montblanc), "chamonixx")
        assertEquals(listOf("Chamonix"), typo.map { it.hit.entry.name })
        assertEquals("montblanc", typo.single().json()["pack"])
    }

    @Test
    fun limitApplies() {
        assertEquals(2, MultiPackSearch.search(listOf(rainier, montblanc), "m", limit = 2).size)
        assertEquals(emptyList(), MultiPackSearch.search(emptyList(), "mont"))
    }
}
