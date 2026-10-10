package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.attribution.PackAttribution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnlineCreditsTest {
    private val server = "http://10.0.2.2:8765"

    @Test
    fun aReportWithOnlyTheDemStillCreditsTheMap() {
        // What the server reported right after a move to Kilimanjaro: the DEM only.
        val ids = OnlineService.withDefaults(listOf("copernicus"))
        assertTrue(ids.containsAll(listOf("copernicus", "osm", "openfreemap", "openmaptiles")), "$ids")
        assertTrue("3dep" !in ids, "the report's elevation sources are kept as reported")
        val credits = OnlineService.creditsFor(server, listOf("copernicus"), demServed = 12, osmServed = 9, osmAttributionHtml = null)
        assertEquals(listOf("online-dem", "online-osm"), credits.map { it.role })
        assertEquals(listOf("copernicus"), credits[0].metadata["provenance"])
        assertEquals(listOf("osm", "openfreemap", "openmaptiles"), credits[1].metadata["provenance"])
        // And the composer credits both kinds.
        val composed = PackAttribution.compose(credits)
        assertTrue(composed.compact.contains("OpenStreetMap"), composed.compact)
        assertTrue(composed.compact.contains("Elevation: Copernicus"), composed.compact)
    }

    @Test
    fun bothKindsAsReportedAndOnlyWhatWasServed() {
        val report = listOf("3dep", "osm", "openfreemap", "openmaptiles", "unknown-id")
        val both = OnlineService.creditsFor(server, report, demServed = 1, osmServed = 1, osmAttributionHtml = "&copy; OSM")
        assertEquals(listOf("3dep"), both[0].metadata["provenance"])
        assertEquals(listOf("osm", "openfreemap", "openmaptiles"), both[1].metadata["provenance"])
        assertEquals("&copy; OSM", both[1].metadata["attribution"])
        assertEquals(listOf("online-osm"), OnlineService.creditsFor(server, report, 0, 3, null).map { it.role })
        assertEquals(emptyList(), OnlineService.creditsFor(server, report, 0, 0, null))
        // No report yet: the live server's defaults.
        assertEquals(OnlineService.DEFAULT_ONLINE_IDS.sorted(), OnlineService.withDefaults(null).sorted())
    }
}
