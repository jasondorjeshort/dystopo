package io.github.jasondorjeshort.dystopo.tilebuild

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttributionTest {
    @Test
    fun htmlCarriesOsmOdblAndOpenMapTiles() {
        assertTrue("&copy; OpenStreetMap contributors" in Attribution.HTML)
        assertTrue("ODbL" in Attribution.HTML)
        assertTrue(Attribution.OSM_COPYRIGHT_URL in Attribution.HTML)
        assertTrue(Attribution.ODBL_URL in Attribution.HTML)
        assertTrue("&copy; OpenMapTiles" in Attribution.HTML)
        assertTrue(Attribution.OPENMAPTILES_URL in Attribution.HTML)
    }

    @Test
    fun textCarriesTheSameCredits() {
        assertTrue("© OpenStreetMap contributors" in Attribution.TEXT)
        assertTrue("ODbL" in Attribution.TEXT)
        assertTrue("© OpenMapTiles" in Attribution.TEXT)
    }

    @Test
    fun extraMetadataRecordsLicenseSourcesAndClip() {
        val meta = Attribution.extraMetadata("liechtenstein", doubleArrayOf(-121.86, 46.78, -121.66, 46.93))
        assertEquals("ODbL-1.0", meta["dystopo:license"])
        assertEquals("liechtenstein", meta["dystopo:extract"])
        assertEquals("-121.86,46.78,-121.66,46.93", meta["dystopo:clip_bbox"])
        assertTrue(meta.getValue("dystopo:sources").contains("OpenStreetMap"))
        assertTrue(meta.getValue("dystopo:sources").contains("Natural Earth"))
        assertTrue(meta.keys.all { it.startsWith("dystopo:") })
        assertTrue("dystopo:clip_bbox" !in Attribution.extraMetadata("x", null))
    }

    /** T264: the world overview holds no OSM data: Natural Earth's credit and licence, the schema's credit kept. */
    @Test
    fun naturalEarthOnlyCreditsNaturalEarth() {
        val meta = Attribution.extraMetadata("natural-earth", null, Attribution.WORLD_CREDIT_IDS, Attribution.OUTDOORS_SCHEMA, "outdoors", naturalEarthOnly = true)
        assertEquals("LicenseRef-PublicDomain", meta["dystopo:license"])
        assertTrue("Natural Earth" in meta.getValue("dystopo:attribution"))
        assertTrue("OpenStreetMap" !in meta.getValue("dystopo:attribution"))
        assertTrue("© OpenMapTiles" in Attribution.WORLD_TEXT)
        assertEquals("natural-earth,openmaptiles", meta["dystopo:provenance"])
    }
}
