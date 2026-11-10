package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.render.ContourUnits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T251: the long-press card's first line on the position marker, one per look, and which looks draw hollow or grey. */
class PuckTextTest {
    private val m = ContourUnits.METERS
    private val ft = ContourUnits.FEET

    @Test
    fun eachLookNamesItself() {
        assertEquals("Your position (GPS, ±8 m)", PuckText.line(LocationPuck.Mode.GPS, 7.6, 1_000, false, m))
        assertEquals("Your position (GPS)", PuckText.line(LocationPuck.Mode.GPS, null, 1_000, false, m))
        assertEquals("Your position, roughly (a poor GPS fix, ±395 ft)", PuckText.line(LocationPuck.Mode.FLAGGED, 120.4, 1_000, false, ft))
        assertEquals("Your position, roughly (network location, ±500 m)", PuckText.line(LocationPuck.Mode.FLAGGED, 500.0, 1_000, true, m))
        assertEquals("Estimated position, about 80 m", PuckText.line(LocationPuck.Mode.ESTIMATE, 72.4, 1_000, false, m))
        assertEquals("No position, last known 7 min ago", PuckText.line(LocationPuck.Mode.STALE, 5.0, 7 * 60_000L + 10_000, false, m))
        assertEquals("No position, last known 12 min ago", PuckText.line(LocationPuck.Mode.LOST, 50.0, 12 * 60_000L, false, m))
    }

    @Test
    fun theLooks() {
        assertTrue(LocationPuck.Mode.FLAGGED.hollow && LocationPuck.Mode.ESTIMATE.hollow)
        assertTrue(LocationPuck.Mode.STALE.grey && LocationPuck.Mode.LOST.grey)
        assertFalse(LocationPuck.Mode.GPS.hollow || LocationPuck.Mode.GPS.grey)
        assertEquals(listOf("gps", "estimate", "lost", "flagged", "stale"), LocationPuck.Mode.entries.map { it.code })
    }
}
