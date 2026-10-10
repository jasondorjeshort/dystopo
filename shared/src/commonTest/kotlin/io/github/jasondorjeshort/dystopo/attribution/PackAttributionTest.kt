package io.github.jasondorjeshort.dystopo.attribution

import io.github.jasondorjeshort.dystopo.sources.SourceKind
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackAttributionTest {
    // Metadata as the Rainier demo packs carry it (devtool tiles --pmtiles, tilebuild --profile outdoors).
    private val demMeta = mapOf(
        "name" to "rainier-dem",
        "attribution" to "Elevation: USGS 3D Elevation Program (3DEP) 1/3 arc-second DEM, courtesy of the U.S. Geological Survey (public domain).",
        "provenance" to listOf("3dep", "3dep-1m", "geoid18", "egm2008"),
    )
    private val osmMeta = mapOf(
        "name" to "dystopo-outdoors",
        "attribution" to "<a href=\"https://www.openstreetmap.org/copyright\" target=\"_blank\">&copy; OpenStreetMap contributors</a> " +
            "(<a href=\"https://opendatacommons.org/licenses/odbl/1-0/\" target=\"_blank\">ODbL 1.0</a>) " +
            "<a href=\"https://www.openmaptiles.org/\" target=\"_blank\">&copy; OpenMapTiles</a>",
        "dystopo:license" to "ODbL-1.0",
        "dystopo:provenance" to "osm,osm-water-polygons,natural-earth,openmaptiles",
    )

    private fun rainier(vararg app: String) = PackAttribution.compose(
        listOf(PackMetadata("osm", "osm.pmtiles", osmMeta), PackMetadata("dem", "dem.pmtiles", demMeta)),
        app.toList(),
    )

    @Test
    fun unescapesNamedNumericAndHexEntities() {
        assertEquals("© OSM & friends <x> \"q\"", HtmlText.unescape("&copy; OSM &amp; friends &lt;x&gt; &quot;q&quot;"))
        assertEquals("©©©", HtmlText.unescape("&#169;&#xA9;&#Xa9;"))
        assertEquals("&copy;", HtmlText.unescape("&amp;copy;"), "one pass only")
        assertEquals("&bogus; &#0; &#xFFFFFFF;", HtmlText.unescape("&bogus; &#0; &#xFFFFFFF;"))
        assertEquals("🗻", HtmlText.unescape("&#x1F5FB;"))
        assertEquals("plain", HtmlText.unescape("plain"))
    }

    @Test
    fun htmlCreditBecomesPlainText() {
        assertEquals("© OpenStreetMap contributors (ODbL 1.0) © OpenMapTiles", HtmlText.toPlain(osmMeta.getValue("attribution") as String))
    }

    @Test
    fun readsBothProvenanceFields() {
        assertEquals(listOf("3dep", "3dep-1m", "geoid18", "egm2008"), PackAttribution.registryIds(demMeta))
        assertEquals(listOf("osm", "osm-water-polygons", "natural-earth", "openmaptiles"), PackAttribution.registryIds(osmMeta))
        assertEquals(listOf("a", "b"), PackAttribution.registryIds(mapOf("provenance" to listOf("a"), "dystopo:provenance" to " a, b ,")))
        assertEquals(emptyList(), PackAttribution.registryIds(mapOf("attribution" to "x")))
    }

    @Test
    fun rainierCreditsEveryPackSourceAndTheFont() {
        val c = rainier(PackAttribution.GLYPH_FONT_ID)
        val ids = c.sources.map { it.source.id }
        assertEquals(
            SourceRegistry.builtIn.ordered(listOf("3dep", "3dep-1m", "geoid18", "egm2008", "osm", "osm-water-polygons", "natural-earth", "openmaptiles", "noto-sans")),
            ids,
        )
        assertEquals(listOf("dem"), c.sources.first { it.source.id == "geoid18" }.usedBy)
        assertEquals(listOf("osm"), c.sources.first { it.source.id == "osm" }.usedBy)
        assertEquals(listOf("app"), c.sources.first { it.source.id == "noto-sans" }.usedBy)
        assertEquals("ODbL-1.0", c.packs.first { it.role == "osm" }.license)
        assertTrue(c.packs.all { it.unknownIds.isEmpty() })
    }

    @Test
    fun compactLineIsPlainAndKeepsTheOsmCredit() {
        val line = rainier(PackAttribution.GLYPH_FONT_ID).compact
        assertEquals("© OpenStreetMap contributors (ODbL 1.0) © OpenMapTiles · Elevation: USGS · Sources", line)
        assertFalse('&' in line || '<' in line)
    }

    @Test
    fun copernicusAndUnknownIdsAreHandled() {
        val meta = mapOf("attribution" to "x", "provenance" to listOf("copernicus", "3dep", "from-the-future"))
        val c = PackAttribution.compose(listOf(PackMetadata("dem", "dem.pmtiles", meta)))
        assertEquals(listOf("copernicus", "3dep"), c.packs.single().registryIds)
        assertEquals(listOf("from-the-future"), c.packs.single().unknownIds)
        assertTrue(c.compact.startsWith("Elevation: Copernicus, USGS"))
    }

    @Test
    fun packWithoutIdsKeepsItsOwnCredit() {
        val c = PackAttribution.compose(listOf(PackMetadata("dem", "old.pmtiles", mapOf("attribution" to "Terrain &copy; someone"))))
        assertEquals("Terrain © someone · Sources", c.compact)
        assertTrue(c.sources.isEmpty())
    }

    @Test
    fun fontEntryIsInTheRegistry() {
        val f = SourceRegistry.builtIn.getValue(PackAttribution.GLYPH_FONT_ID)
        assertEquals(SourceKind.FONT, f.kind)
        assertEquals("OFL-1.1", f.license)
        assertTrue("Noto Sans" in f.attribution)
    }

    @Test
    fun jsonHasTheFieldsTheDeviceRouteReturns() {
        val j = rainier().json()
        assertEquals(setOf("compact", "packs", "sources"), j.keys)
        @Suppress("UNCHECKED_CAST")
        val first = (j["sources"] as List<Map<String, Any?>>).first()
        for (k in listOf("id", "name", "kind", "provider", "license", "licenseName", "licenseUrl", "attribution", "url", "usedBy")) assertTrue(k in first, k)
    }

    @Test
    fun repeatedNoticesAppearOnce() {
        // T49: the Rainier pack (tilebuild) and a server-built pack both credit OSM and OpenMapTiles.
        val a = "\u00a9 OpenStreetMap contributors (ODbL 1.0) \u00a9 OpenMapTiles"
        val b = "\u00a9 OpenStreetMap contributors (ODbL 1.0) OpenFreeMap \u00a9 OpenMapTiles"
        assertEquals(listOf(b), PackAttribution.mergeCredits(listOf(a, b)))
        assertEquals(listOf(b), PackAttribution.mergeCredits(listOf(b, a)))
        assertEquals(listOf(a, "Other credit"), PackAttribution.mergeCredits(listOf(a, a, "Other credit")))
        val c = PackAttribution.compose(
            listOf(
                PackMetadata("osm", "rainier-outdoors.pmtiles", mapOf("attribution" to a, "dystopo:provenance" to "osm,openmaptiles")),
                PackMetadata("osm", "montblanc-outdoors.pmtiles", mapOf("attribution" to b, "dystopo:provenance" to "osm,openfreemap,openmaptiles")),
            ),
        )
        assertEquals(1, Regex("OpenStreetMap").findAll(c.compact).count(), c.compact)
        assertEquals(1, Regex("OpenMapTiles").findAll(c.compact).count(), c.compact)
    }
}
