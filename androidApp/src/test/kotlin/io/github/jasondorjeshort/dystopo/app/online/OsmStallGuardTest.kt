package io.github.jasondorjeshort.dystopo.app.online

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T123: OpenFreeMap's stall guard and the debug base-URL override (finding 2026-10-16-cold-view-gap). */
class OsmStallGuardTest {
    private val template = "https://tiles.openfreemap.org/planet/20251015_001001_pt/{z}/{x}/{y}.pbf"

    @Test
    fun baseReplacesOnlySchemeAndHost() {
        assertEquals(template, DirectSources.withOsmBase(template, null))
        assertEquals(template, DirectSources.withOsmBase(template, " "))
        assertEquals("http://10.0.2.2:8753/planet/20251015_001001_pt/{z}/{x}/{y}.pbf", DirectSources.withOsmBase(template, "http://10.0.2.2:8753"))
        assertEquals("http://10.0.2.2:8753/planet/20251015_001001_pt/{z}/{x}/{y}.pbf", DirectSources.withOsmBase(template, "http://10.0.2.2:8753/ignored/path"))
        assertFailsWith<IllegalArgumentException> { DirectSources.withOsmBase(template, "10.0.2.2:8753") }
    }

    @Test
    fun aStalledFetchGivesUpBeforeMapLibreDoes() {
        // A host that accepts and never answers: the first attempt stalls for OSM_STALL_MS, one retry fits, a third does not.
        assertTrue(DirectSources.ofmRetries(1, DirectSources.OSM_STALL_MS))
        assertFalse(DirectSources.ofmRetries(2, 2 * DirectSources.OSM_STALL_MS + DirectSources.OSM_RETRY_DELAY_MS))
        // A slow connect plus a stall leaves no room for a retry that could stall again.
        assertFalse(DirectSources.ofmRetries(1, DirectSources.OSM_CONNECT_MS + DirectSources.OSM_STALL_MS))
        val worstStall = 2 * DirectSources.OSM_STALL_MS + DirectSources.OSM_RETRY_DELAY_MS
        assertTrue(worstStall <= DirectSources.OSM_GUARD_MS && DirectSources.OSM_GUARD_MS < 10_000, "MapLibre's client times out at 10 s")
    }
}
