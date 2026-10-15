package io.github.jasondorjeshort.dystopo.app.areas

import io.github.jasondorjeshort.dystopo.render.ContourUnits
import io.github.jasondorjeshort.dystopo.render.UnitFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T106: the area follow-ups of T91 and T95 that are pure rules (the card's line, Dismiss, a source switch). */
class AreaFollowupsTest {
    @Test
    fun theCorridorLineFollowsTheUnitsSetting() {
        // Paradise to Panorama Point and back, 300 m each side (T95's corridor), and a long drive's 500 m. The numbers
        // are UnitFormat.distance's (T101 changes its rule underneath); what matters here is that they follow the setting.
        for ((len, buf) in listOf(6440.0 to 300.0, 84_000.0 to 500.0)) {
            val metric = corridorLine(len, buf, ContourUnits.METERS)
            val imperial = corridorLine(len, buf, ContourUnits.FEET)
            assertEquals("Corridor ${UnitFormat.distance(len, ContourUnits.METERS)}, ${UnitFormat.distance(buf, ContourUnits.METERS)} each side", metric)
            assertEquals("Corridor ${UnitFormat.distance(len, ContourUnits.FEET)}, ${UnitFormat.distance(buf, ContourUnits.FEET)} each side", imperial)
            assertTrue(Regex("""\d (km|m) """).containsMatchIn(metric) && !Regex("""\d (mi|ft)\b""").containsMatchIn(metric), metric)
            assertTrue(Regex("""\d (mi|ft)\b""").containsMatchIn(imperial) && !Regex("""\d (km|m)\b""").containsMatchIn(imperial), imperial)
        }
        assertEquals("Corridor 6.44 km, 300 m each side", corridorLine(6440.0, 300.0, ContourUnits.METERS))
    }

    @Test
    fun dismissKeepsWhatIsThereOrRemovesAnEmptyArea() {
        assertNull(dismissedState(isPack = false, share = null, bytes = 0, state = "failed"), "no manifest: nothing of it is on the phone")
        assertNull(dismissedState(isPack = false, share = 0.0, bytes = 0, state = "failed"))
        assertEquals("partial", dismissedState(isPack = false, share = 0.42, bytes = 9_000_000, state = "failed"))
        assertEquals("done", dismissedState(isPack = false, share = 1.0, bytes = 22_000_000, state = "done"))
        assertEquals("done", dismissedState(isPack = true, share = null, bytes = 0, state = "done"))
        // An Update refused for an area fetched from another source: the map finds none of its tiles under the source
        // set now, but they are on the phone; Dismiss keeps the area as it was.
        assertEquals("done", dismissedState(isPack = false, share = 0.0, bytes = 22_000_000, state = "done"))
        assertEquals("partial", dismissedState(isPack = false, share = 0.0, bytes = 22_000_000, state = "failed"))
    }

    @Test
    fun anAreaFromAnotherSourceIsOlderInWhatItHolds() {
        assertEquals(listOf("dem", "osm"), otherSourceNames(mapOf("osm" to "20261001", "dem" to "a1b2")))
        assertEquals(listOf("dem", "osm"), otherSourceNames(null), "recorded before T95: both")
        assertEquals(listOf("dem", "osm"), otherSourceNames(emptyMap()))
    }

    @Test
    fun pinnedBytesPerSourceBiggestFirstWithWhetherThePhoneReadsIt() {
        val by = mapOf("10.0.2.2_8765" to (120 to 9_000_000L), "sources" to (40 to 3_000_000L), "10.0.2.2_8800" to (300 to 22_000_000L))
        val out = pinnedBySource(by, current = listOf("10.0.2.2_8800"))
        assertEquals(listOf("10.0.2.2_8800", "10.0.2.2_8765", "sources"), out.map { it["source"] })
        assertEquals(listOf(true, false, false), out.map { it["inUse"] })
        assertEquals(300, out[0]["tiles"])
        assertEquals(22_000_000L, out[0]["bytes"])
        assertEquals(emptyList(), pinnedBySource(emptyMap(), listOf("sources")))
    }
}
