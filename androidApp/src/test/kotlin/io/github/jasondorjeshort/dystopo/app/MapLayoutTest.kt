package io.github.jasondorjeshort.dystopo.app

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.jasondorjeshort.dystopo.ui.OverlayElement
import io.github.jasondorjeshort.dystopo.ui.OverlayIds
import io.github.jasondorjeshort.dystopo.ui.OverlayPlacement
import io.github.jasondorjeshort.dystopo.ui.OverlayState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** T224: what `GET /layout` reports from the elements' rectangles ([MapLayout]); T241: the nodes' record ([MeasuredControls]). */
class MapLayoutTest {
    private fun seen(id: String, vararg r: Float, state: String = "shown", within: String? = null) =
        MapLayout.Seen(id, if (r.isEmpty()) null else r, state, null, within)

    @Test
    fun aPairIsAnOverlapFromOneWholePixel() {
        val list = listOf(
            seen(OverlayIds.NAV_CARD, 20f, 200f, 1060f, 900f),
            seen(OverlayIds.TRACK_BAR, 800f, 899f, 1060f, 1000f), // shares one whole row with the card
            seen(OverlayIds.ROUTES, 900f, 1000f, 1060f, 1100f), // touches the bar's bottom edge
            seen(OverlayIds.ONLINE, 0f, 900.5f, 100f, 1000f), // a sub-pixel share is the rounding of two bounds
            seen(OverlayIds.STRIP, state = "hidden"),
        )
        val o = MapLayout.overlaps(list)
        assertEquals(1, o.size, "$o")
        assertEquals(OverlayIds.NAV_CARD, o[0]["a"])
        assertEquals(OverlayIds.TRACK_BAR, o[0]["b"])
        assertEquals("navigationCard", o[0]["aName"])
        assertEquals(listOf(260, 1), o[0]["sizePx"])
    }

    @Test
    fun anElementInsideAnotherByDesignIsNoOverlap() {
        // T241: the Online chip's card lies in the online column (M8), which grows with it; another element over it still counts.
        val o = MapLayout.overlaps(listOf(
            seen(OverlayIds.ONLINE, 20f, 200f, 500f, 700f),
            seen(MapLayout.ONLINE_CARD, 20f, 300f, 500f, 700f, within = OverlayIds.ONLINE),
            seen(OverlayIds.NAV_CARD, 0f, 650f, 1080f, 900f),
        ))
        assertEquals(listOf(OverlayIds.ONLINE to OverlayIds.NAV_CARD, MapLayout.ONLINE_CARD to OverlayIds.NAV_CARD), o.map { it["a"] to it["b"] }, "$o")
        assertEquals(MapLayout.ONLINE_CARD, o[1]["aName"]) // no M-number: named by its manifest id
    }

    @Test
    fun theRulesChangesAreListed() {
        val placed = OverlayPlacement(
            listOf(
                OverlayElement(OverlayIds.TRACK_BAR, null, OverlayState.HIDDEN, "noRoom"),
                OverlayElement(OverlayIds.STRIP, null, OverlayState.HIDDEN, "yieldOrder"),
            ),
            buttonsDrawn = 1, entriesDrawn = 0, sideColumn = false, cardColumns = 1,
        )
        val d = MapLayout.differences(listOf(seen(OverlayIds.TRACK_BAR, 0f, 0f, 1f, 1f), seen(OverlayIds.STRIP, state = "hidden"), seen(OverlayIds.ROUTES, 0f, 0f, 1f, 1f)), placed)
        assertEquals(1, d.size, "$d")
        assertEquals(OverlayIds.TRACK_BAR, d[0]["id"])
        assertEquals("shown", d[0]["today"])
        assertEquals("hidden", d[0]["rule"])
    }

    @Test
    fun thePlacementIsComputedOncePerPass() {
        val c = MeasuredControls()
        val chips = c.attach("waypointAndTracks", null)
        val card = c.attach("planningCard", null)
        // A pass: every node that needs it is measured, then each placement block asks for the placement.
        c.measured(chips, IntSize(300, 120))
        c.measured(card, IntSize(1000, 600))
        val first = c.placement()
        assertSame(first, c.placement(), "a second node placed in the same pass gets the same placement")
        assertEquals(1, c.computed)
        // The next pass begins with the next measure, and its placement is computed again, once.
        c.measured(card, IntSize(1000, 640))
        assertEquals(first.pass + 1, c.pass)
        val second = c.placement()
        assertEquals(first.pass + 1, second.pass)
        assertSame(second, c.placement())
        assertEquals(2, c.computed)
        // A placement with no measure before it (a node moved, not resized) computes nothing.
        c.placed(chips, floatArrayOf(0f, 0f, 1f, 1f))
        assertSame(second, c.placement())
        assertEquals(2, c.computed)
        // Nothing moves until T239: every element stays where its own parent put it.
        assertEquals(IntOffset.Zero, second.offset("planningCard"))
    }

    @Test
    fun thePlacementReadsThisPassesSizes() {
        val c = MeasuredControls()
        val card = c.attach("navigationCard", null)
        val chips = c.attach("waypointAndTracks", null)
        c.measured(card, IntSize(1040, 300))
        c.measured(chips, IntSize(280, 110))
        assertEquals(IntSize(1040, 300), c.placement().size("navigationCard"))
        c.placed(card, floatArrayOf(20f, 150f, 1060f, 450f))
        c.placed(chips, floatArrayOf(780f, 470f, 1060f, 580f))
        // The turn text wraps: the card grows in this pass, and a node placed in it reads the new size, not the last pass's (T146).
        c.measured(card, IntSize(1040, 360))
        val p = c.placement()
        assertEquals(IntSize(1040, 360), p.size("navigationCard"))
        assertEquals(IntSize(280, 110), p.size("waypointAndTracks"), "a node not measured in this pass keeps its size")
    }

    @Test
    fun aHiddenElementKeepsItsSize() {
        val c = MeasuredControls()
        val online = c.attach("onlineIndicator", null)
        c.measured(online, IntSize(400, 90))
        c.placement()
        c.placed(online, floatArrayOf(21f, 190f, 421f, 280f))
        assertTrue(online.onScreen)
        assertContentEquals(floatArrayOf(21f, 190f, 421f, 280f), c.rectOf("onlineIndicator"))
        // Measured and not placed (the column with no room above the credit line): hidden, and its size stays.
        c.measured(online, IntSize(400, 90))
        c.placement()
        assertFalse(online.onScreen)
        assertNull(c.rectOf("onlineIndicator"))
        assertEquals(IntSize(400, 90), c.placement().size("onlineIndicator"))
        assertEquals(IntSize(400, 90), c.sizeOf("onlineIndicator"))
        assertEquals(emptySet(), c.onScreen())
        // Placed again with no new measure: back on screen.
        c.placed(online, floatArrayOf(21f, 190f, 421f, 280f))
        assertTrue(online.onScreen)
        // Its layout node no longer placed by its parent (the scale bar's line with no room), as its semantics say: hidden, size kept.
        assertNull(c.rectOf("onlineIndicator", laidOut = setOf("search")))
        assertEquals(emptySet(), c.onScreen(laidOut = setOf("search")))
        assertContentEquals(floatArrayOf(21f, 190f, 421f, 280f), c.rectOf("onlineIndicator", laidOut = setOf("onlineIndicator")))
        assertEquals(IntSize(400, 90), c.sizeOf("onlineIndicator"))
        // Out of the composition: the size goes with it.
        c.detach(online)
        assertNull(c.sizeOf("onlineIndicator"))
        assertNull(c.placement().size("onlineIndicator"))
    }

    @Test
    fun aCardInAHiddenColumnIsNotOnScreen() {
        val c = MeasuredControls()
        val column = c.attach("onlineIndicator", null)
        val card = c.attach("onlineCard", column)
        c.measured(column, IntSize(683, 474))
        c.measured(card, IntSize(683, 285))
        c.placement()
        c.placed(column, floatArrayOf(21f, 190f, 704f, 664f))
        c.placed(card, floatArrayOf(21f, 278f, 704f, 563f))
        assertEquals(setOf("onlineIndicator", "onlineCard"), c.onScreen())
        // The column is measured and not placed; the card inside it, not measured again, is not placed either.
        c.measured(column, IntSize(683, 474))
        assertEquals(emptySet(), c.onScreen())
        assertNull(c.rectOf("onlineCard"))
        // An empty rectangle (clipped out of the window) is not on screen.
        c.placed(column, floatArrayOf(21f, 190f, 704f, 664f))
        c.placed(card, floatArrayOf(21f, 664f, 704f, 664f))
        assertEquals(setOf("onlineIndicator"), c.onScreen())
    }

    @Test
    fun getControlsSaysWhatIsOnScreen() {
        // A chip column hidden above the credit line is composed (tagged) but not on screen.
        val json = ControlsRoutes.json(mapOf("search" to 1, "onlineIndicator" to 1), setOf("search"))
        @Suppress("UNCHECKED_CAST")
        val items = (json["items"] as List<Map<String, Any?>>).associateBy { it["id"] }
        assertEquals(listOf(1, true), listOf(items.getValue("search")["tagged"], items.getValue("search")["onScreen"]))
        assertEquals(listOf(1, false), listOf(items.getValue("onlineIndicator")["tagged"], items.getValue("onlineIndicator")["onScreen"]))
        assertEquals(false, items.getValue("tripCard")["onScreen"])
        assertFalse(items.values.filter { it.containsKey("row") }.any { it.containsKey("onScreen") }, "a settings row has no onScreen")
    }
}
