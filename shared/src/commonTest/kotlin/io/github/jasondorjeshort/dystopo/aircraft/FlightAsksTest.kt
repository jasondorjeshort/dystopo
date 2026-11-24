package io.github.jasondorjeshort.dystopo.aircraft

import io.github.jasondorjeshort.dystopo.aircraft.FlightAsks.Inputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T364: who asks the flights' poller (the map; the sky views as one owner), and what the poller does for them. */
class FlightAsksTest {
    @Test
    fun trackingOffNobodyAsksAndTheTickerStops() {
        for (i in listOf(Inputs(tracking = false), Inputs(tracking = false, skyOpen = true), Inputs(tracking = false, mapShown = false, skyShown = false))) {
            assertEquals(emptySet(), FlightAsks.askers(i), "$i")
            assertEquals("off", FlightAsks.mapReason(i))
            assertEquals("off", FlightAsks.skyReason(i))
            assertTrue(FlightAsks.mapClears(FlightAsks.mapReason(i)), "off clears the map's marks")
        }
        assertEquals(FlightAsks.Plan(ticker = false, rebuild = false, poll = false), FlightAsks.plan(false, emptySet()))
        assertEquals(FlightAsks.Plan(ticker = false, rebuild = false, poll = false), FlightAsks.plan(false, setOf(FlightAsks.MAP)), "a stale ask is ignored once tracking is off")
    }

    @Test
    fun theMapShownAsks() {
        val i = Inputs(tracking = true)
        assertNull(FlightAsks.mapReason(i))
        assertEquals(setOf(FlightAsks.MAP), FlightAsks.askers(i))
        assertEquals("closed", FlightAsks.skyReason(i))
        assertEquals(FlightAsks.Plan(ticker = true, rebuild = true, poll = true), FlightAsks.plan(true, FlightAsks.askers(i)))
    }

    @Test
    fun theMapHiddenWithASkyViewOpenLeavesTheSkyViewsAsking() {
        val i = Inputs(tracking = true, mapShown = false, skyOpen = true)
        assertEquals("hidden", FlightAsks.mapReason(i))
        assertNull(FlightAsks.skyReason(i))
        assertEquals(setOf(FlightAsks.SKY), FlightAsks.askers(i))
        assertEquals(FlightAsks.Plan(ticker = true, rebuild = true, poll = true), FlightAsks.plan(true, FlightAsks.askers(i)))
        // Shown on the map but covered by the sky view: the map waits with its marks kept, the sky views ask.
        val covered = i.copy(mapShown = true)
        assertEquals("horizonViewOpen", FlightAsks.mapReason(covered))
        assertFalse(FlightAsks.mapClears(FlightAsks.mapReason(covered)), "a covered map keeps its marks")
        assertEquals(setOf(FlightAsks.SKY), FlightAsks.askers(covered))
        // The sky views' own toggle is theirs alone: off there, the map shown still asks once the sky view closes.
        assertEquals("hidden", FlightAsks.skyReason(covered.copy(skyShown = false)))
        assertEquals(setOf(FlightAsks.MAP), FlightAsks.askers(Inputs(tracking = true, skyShown = false)))
    }

    @Test
    fun allHiddenNothingIsBuiltOrRequestedButTheTickerWaits() {
        val i = Inputs(tracking = true, mapShown = false, skyOpen = true, skyShown = false)
        assertEquals(emptySet(), FlightAsks.askers(i))
        assertEquals(emptySet(), FlightAsks.askers(Inputs(tracking = true, mapShown = false)), "the map hidden, no sky view open")
        assertTrue(FlightAsks.mapClears("hidden"), "hiding the map clears its marks once")
        assertEquals(FlightAsks.Plan(ticker = true, rebuild = false, poll = false), FlightAsks.plan(true, emptySet()))
    }

    @Test
    fun theOtherReasonsInOrder() {
        assertEquals("hidden", FlightAsks.mapReason(Inputs(tracking = true, mapShown = false, foreground = false)), "hidden before background")
        assertEquals("background", FlightAsks.mapReason(Inputs(tracking = true, foreground = false)))
        assertEquals("noView", FlightAsks.mapReason(Inputs(tracking = true, mapHasView = false)))
        assertEquals("background", FlightAsks.skyReason(Inputs(tracking = true, skyOpen = true, foreground = false)))
        assertEquals("noFix", FlightAsks.skyReason(Inputs(tracking = true, skyOpen = true, skyHasFix = false)))
        assertFalse(FlightAsks.mapClears("background"))
        assertFalse(FlightAsks.mapClears(null))
    }
}
