package io.github.jasondorjeshort.dystopo.app.firstrun

import io.github.jasondorjeshort.dystopo.packs.PackBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T332: the map's message asks whether the phone holds a map (a region or a downloaded area), and only then whether the
 * view lies outside every one; a phone with its surroundings downloaded as an area was shown the first-run card.
 */
class MapMessageTest {
    private val rainier = PackBounds(-121.95, 46.70, -121.55, 46.98)
    private val paradise = PackBounds(-121.76, 46.77, -121.71, 46.80)
    private val inside = PackBounds(-121.80, 46.80, -121.70, 46.90)
    private val montBlanc = PackBounds(6.80, 45.80, 6.92, 45.87)
    private val acrossRainierEdge = PackBounds(-122.10, 46.60, -121.90, 46.75)

    @Test
    fun nothingHeldShowsTheFirstRun() {
        val m = MapMessage.choose(opened = true, regions = emptyList(), areas = emptyList(), view = inside, online = false)
        assertEquals(MapMessage.NOTHING_HELD, m)
        assertTrue(m.firstRun)
        assertTrue(m.hidesControls)
        val o = MapMessage.choose(opened = true, regions = emptyList(), areas = emptyList(), view = inside, online = true)
        assertEquals(MapMessage.NOTHING_HELD_ONLINE, o)
        assertTrue(o.firstRun)
        assertFalse(o.hidesControls)
    }

    @Test
    fun packsStillOpeningSayNothing() {
        for (online in listOf(false, true)) {
            val m = MapMessage.choose(opened = false, regions = emptyList(), areas = emptyList(), view = montBlanc, online = online)
            assertEquals(MapMessage.OPENING, m)
            assertFalse(m.firstRun)
            assertFalse(m.hidesControls)
        }
    }

    @Test
    fun aRegionAndTheViewInside() {
        assertEquals(MapMessage.NONE, MapMessage.choose(true, listOf(rainier), emptyList(), inside, online = false))
    }

    @Test
    fun anAreaOnlyAndTheViewInsideIsAHeldMap() {
        val m = MapMessage.choose(true, emptyList(), listOf(paradise), paradise, online = false)
        assertEquals(MapMessage.NONE, m)
        assertFalse(m.firstRun) // the first-run card never shows to a phone that holds a downloaded area
        assertEquals(MapMessage.NONE, MapMessage.choose(true, emptyList(), listOf(paradise), montBlanc, online = true))
    }

    @Test
    fun heldAndTheViewOutside() {
        val m = MapMessage.choose(true, listOf(rainier), listOf(paradise), montBlanc, online = false)
        assertEquals(MapMessage.OUTSIDE, m)
        assertFalse(m.firstRun)
        assertFalse(m.hidesControls) // Recentre, the scale bar and the view switch stay
        assertEquals(MapMessage.OUTSIDE, MapMessage.choose(true, emptyList(), listOf(paradise), montBlanc, online = false))
        // An online source draws the view: nothing to say.
        assertEquals(MapMessage.NONE, MapMessage.choose(true, listOf(rainier), listOf(paradise), montBlanc, online = true))
        // The map not up yet: nothing to say.
        assertEquals(MapMessage.NONE, MapMessage.choose(true, listOf(rainier), emptyList(), null, online = false))
    }

    @Test
    fun aViewStraddlingAnEdgeCountsAsInside() {
        assertTrue(MapMessage.covers(listOf(rainier), acrossRainierEdge))
        assertEquals(MapMessage.NONE, MapMessage.choose(true, listOf(rainier), emptyList(), acrossRainierEdge, online = false))
        // Touching edges count; a view just past one does not.
        assertTrue(MapMessage.covers(listOf(rainier), PackBounds(-121.55, 46.98, -121.40, 47.10)))
        assertFalse(MapMessage.covers(listOf(rainier), PackBounds(-121.54, 46.99, -121.40, 47.10)))
        // A view wrapping the whole region also covers it.
        assertTrue(MapMessage.covers(listOf(paradise), PackBounds(-130.0, 40.0, -110.0, 50.0)))
    }

    @Test
    fun aViewAcrossTheAntimeridian() {
        val synthetic = PackBounds(177.0, -19.0, 179.9, -16.0)
        assertTrue(MapMessage.covers(listOf(synthetic),PackBounds(178.0, -20.0, -178.0, -15.0)))
        assertFalse(MapMessage.covers(listOf(rainier), PackBounds(178.0, -20.0, -178.0, -15.0)))
    }

    @Test
    fun whichAreasCount() {
        assertTrue(MapMessage.heldArea("done", isPack = false))
        assertTrue(MapMessage.heldArea("partial", isPack = false))
        assertFalse(MapMessage.heldArea("tiles", isPack = false)) // still downloading: the card's progress line says so
        assertFalse(MapMessage.heldArea("failed", isPack = false))
        assertFalse(MapMessage.heldArea("cancelled", isPack = false))
        assertFalse(MapMessage.heldArea("done", isPack = true)) // a pack area is a region, counted there
    }

    // T342: a map switched off is held and covers nothing: every map off never shows the first-run card; offline the view
    // reads the outside message with its action, online nothing.
    @Test
    fun everyMapOffReadsTheOutsideMessageAndNeverTheFirstRun() {
        val m = MapMessage.choose(opened = true, regions = emptyList(), areas = emptyList(), view = inside, online = false, heldOff = true)
        assertEquals(MapMessage.OUTSIDE, m)
        assertFalse(m.firstRun)
        assertFalse(m.hidesControls)
        assertEquals(MapMessage.NONE, MapMessage.choose(true, emptyList(), emptyList(), inside, online = true, heldOff = true))
        assertEquals(MapMessage.NOTHING_HELD, MapMessage.choose(true, emptyList(), emptyList(), inside, online = false, heldOff = false))
        // One map on and one off: the one on decides, as before.
        assertEquals(MapMessage.NONE, MapMessage.choose(true, listOf(rainier), emptyList(), inside, online = false, heldOff = true))
    }
    // end T342
}
