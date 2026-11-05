package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.ui.OverlayElement
import io.github.jasondorjeshort.dystopo.ui.OverlayIds
import io.github.jasondorjeshort.dystopo.ui.OverlayPlacement
import io.github.jasondorjeshort.dystopo.ui.OverlayState
import kotlin.test.Test
import kotlin.test.assertEquals

/** T224: what `GET /layout` reports from the rectangles the elements publish ([MapLayout]). */
class MapLayoutTest {
    private fun seen(id: String, vararg r: Float, state: String = "shown", derived: Boolean = false) =
        MapLayout.Seen(id, if (r.isEmpty()) null else r, state, null, derived)

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
        assertEquals(false, o[0]["derived"])
    }

    @Test
    fun aDerivedRectangleIsSaid() {
        val o = MapLayout.overlaps(listOf(seen(OverlayIds.TRIP_CARD, 0f, 0f, 100f, 100f, derived = true), seen(OverlayIds.STRIP, 50f, 50f, 200f, 200f)))
        assertEquals(true, o.single()["derived"])
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
}
