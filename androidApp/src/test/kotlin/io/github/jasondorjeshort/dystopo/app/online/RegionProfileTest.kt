package io.github.jasondorjeshort.dystopo.app.online

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** T64: what "Download this region" says about the vector profile the server built. */
class RegionProfileTest {
    @Test
    fun labelsNameTheProfileAndItsSource() {
        assertNull(RegionDownload.packLabel(null))
        assertNull(RegionDownload.packLabel(mapOf("status" to "queued")))
        val fromExtract = mapOf(
            "profile" to "outdoors", "osmProfile" to "outdoors", "osmSource" to "extract",
            "provenance" to mapOf("osmExtract" to mapOf("name" to "washington", "date" to "2026-10-08T20:21:06Z")),
        )
        assertEquals("outdoors, washington extract 2026-10-08", RegionDownload.packLabel(fromExtract))
        assertEquals("outdoors, Overpass", RegionDownload.packLabel(mapOf("profile" to "outdoors", "osmProfile" to "outdoors", "osmSource" to "overpass")))
        assertEquals(
            "OpenMapTiles (outdoors not available here)",
            RegionDownload.packLabel(mapOf("profile" to "outdoors", "osmProfile" to "openmaptiles", "osmSource" to "openfreemap")),
        )
        assertEquals("OpenMapTiles", RegionDownload.packLabel(mapOf("profile" to "openmaptiles", "osmProfile" to "openmaptiles")))
    }
}
