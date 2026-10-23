package io.github.jasondorjeshort.dystopo.app.areas

import io.github.jasondorjeshort.dystopo.render.ContourUnits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T130: the trip card's corridor line, the width chips and the corridors' help text. */
class TripCorridorStatusTest {
    private val m = ContourUnits.METERS
    private val ft = ContourUnits.FEET
    private fun rec(state: String, pinned: Boolean = true, bytes: Long = 42L * 1_048_576, error: String? = null) = RecordView(state, pinned, bytes, bytes, error, 1000)

    @Test
    fun eachStateHasItsLineAndActions() {
        assertEquals(CorridorStatus("kept", "Kept offline, ${km()} each side: 42 MB", listOf("stop")), corridorStatus(1000, false, false, null, rec("done"), null, m))
        assertEquals("downloading", corridorStatus(1000, false, true, null, rec("downloading"), 12 to 900, m).state)
        assertTrue("12 of 900 tiles" in corridorStatus(1000, false, false, null, rec("downloading"), 12 to 900, m).text)
        assertEquals(listOf("retry", "stop"), corridorStatus(1000, false, false, null, rec("partial", error = "3 tiles could not be fetched"), null, m).actions)
        assertEquals(CorridorStatus("queued", "Waiting to keep offline, ${km()} each side", listOf("stop")), corridorStatus(1000, false, true, null, null, null, m))
        val over = corridorStatus(1000, false, false, "Offline areas would use about 2100 MB, over their 2 GB budget", null, null, m)
        assertEquals("refused", over.state)
        assertTrue(over.text.startsWith("Not kept offline: Offline areas would use"))
        assertEquals(listOf("areas", "keep"), over.actions)
        assertEquals(CorridorStatus("notKept", CORRIDOR_NOT_KEPT, listOf("keep")), corridorStatus(1000, true, false, null, null, null, m))
        // Stopped, but its area is unpinned on the sheet: still not kept.
        assertEquals("notKept", corridorStatus(1000, true, false, null, rec("done", pinned = false), null, m).state)
        assertEquals("off", corridorStatus(0, false, false, null, null, null, m).state)
        assertEquals("none", corridorStatus(1000, false, false, null, null, null, m).state)
        // In feet the width reads in miles.
        assertTrue("0.6 mi each side" in corridorStatus(1000, false, false, null, rec("done"), null, ft).text)
    }

    @Test
    fun widthChipsAndHelp() {
        assertEquals("Off", corridorWidthChip(0, m))
        assertEquals("500 m", corridorWidthChip(500, m))
        assertEquals(km(), corridorWidthChip(1000, m))
        for (u in ContourUnits.entries) for (w in listOf(0, 1000, 5000)) {
            val h = corridorHelp(w, u)
            assertTrue(h.length <= 330, "${h.length}: $h")
        }
        assertTrue("${km()} each side" in corridorHelp(1000, m))
        assertTrue("not kept offline on their own" in corridorHelp(0, m))
        assertTrue(corridorLine(12_000.0, 1000.0, m, trip = true).startsWith("Trip corridor "))
        assertTrue(corridorLine(12_000.0, 1000.0, m, trip = true).endsWith("${km()} each side"))
    }

    private fun km() = io.github.jasondorjeshort.dystopo.render.UnitFormat.distance(1000.0, m)
}
