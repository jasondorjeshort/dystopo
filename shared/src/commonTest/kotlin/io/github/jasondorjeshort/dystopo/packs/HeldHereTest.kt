package io.github.jasondorjeshort.dystopo.packs

import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.TileCover
import io.github.jasondorjeshort.dystopo.packs.HeldHere.Holding
import io.github.jasondorjeshort.dystopo.packs.HeldHere.Kind
import io.github.jasondorjeshort.dystopo.packs.HeldHere.Verdict
import io.github.jasondorjeshort.dystopo.packs.HeldHere.Want
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T343: [HeldHere], over the two-maps fixture's boxes at Mount Rainier (public). */
class HeldHereTest {
    private val wideBox = PackBounds(-121.95, 46.68, -121.45, 46.98)
    private val fineBox = PackBounds(-121.82, 46.80, -121.68, 46.90)
    /** A view near the summit, inside both maps. */
    private val view = PackBounds(-121.78, 46.83, -121.74, 46.87)

    private fun map(name: String, box: PackBounds, vector: Int?, dem: Int?, off: Boolean = false) =
        Holding(name, Kind.MAP, box, vector, dem, routing = vector != null, off = off)

    private val wide = map("rainier-wide", wideBox, 13, 11)
    private val fine = map("rainier-fine", fineBox, 14, 14)

    private fun area(name: String, box: PackBounds, maxZoom: Int, demMaxZoom: Int? = null): Holding {
        val a = AreaOfInterest(AreaShape.Box(box.minLon, box.minLat, box.maxLon, box.maxLat), 8, maxZoom, name, demMaxZoom)
        val sets = HashMap<Int, io.github.jasondorjeshort.dystopo.areas.TileSet>()
        return Holding(name, Kind.AREA, box, maxZoom, demMaxZoom ?: maxZoom, routing = false, cells = { z, x, y ->
            sets.getOrPut(z) { TileCover.of(a, z) }.contains(x, y)
        })
    }

    @Test
    fun detailsFromZooms() {
        assertEquals("full", HeldHere.detailOf(14, 14))
        assertEquals("full", HeldHere.detailOf(15, 15))
        assertEquals("medium", HeldHere.detailOf(14, 13))
        assertEquals("less", HeldHere.detailOf(13, 13))
        assertEquals(HeldHere.LOW, HeldHere.detailOf(13, 11)) // the fixture's wide map
        assertEquals(HeldHere.LOW, HeldHere.detailOf(14, null)) // a map without elevation
    }

    @Test
    fun aMapThatHoldsTheBoxAtTheDetailIsNothingNew() {
        val h = HeldHere.held(view, Want("full", routing = true), listOf(fine))
        assertEquals(Verdict.ALL, h.verdict)
        assertEquals("rainier-fine", h.wholeAtDetail?.name)
        assertEquals(1.0, h.share)
    }

    @Test
    fun aCoarserMapUnderAFullRequestIsAnUpgrade() {
        val h = HeldHere.held(view, Want("full", routing = true), listOf(wide))
        assertEquals(Verdict.COARSER, h.verdict)
        assertEquals(1.0, h.share)
        assertEquals(0.0, h.shareAtDetail)
        assertEquals(HeldHere.LOW, h.finest?.detail)
        assertNull(h.wholeAtDetail)
        // A medium map-only area under a full request with routing: held whole, but coarser.
        val m = HeldHere.held(view, Want("full", routing = true), listOf(area("us-medium", wideBox, 14, 13)))
        assertEquals(Verdict.COARSER, m.verdict)
        assertEquals("medium", m.finest?.detail)
    }

    @Test
    fun routingIsPartOfWhatIsNew() {
        // A full-detail tile area (map only) under the pack path's full detail with routing: the routing is new.
        val a = area("view-1", wideBox, 15)
        assertEquals(Verdict.COARSER, HeldHere.held(view, Want("full", routing = true), listOf(a)).verdict)
        assertEquals(Verdict.ALL, HeldHere.held(view, Want("full", routing = false), listOf(a)).verdict)
    }

    @Test
    fun anAreaHoldsItsCellsNotItsBox() {
        // An L-shaped polygon whose box holds the view but whose cells hold only part of it.
        val ring = listOf(
            io.github.jasondorjeshort.dystopo.areas.AreaPoint(46.60, -122.10), io.github.jasondorjeshort.dystopo.areas.AreaPoint(46.60, -121.50),
            io.github.jasondorjeshort.dystopo.areas.AreaPoint(46.90, -121.50), io.github.jasondorjeshort.dystopo.areas.AreaPoint(46.90, -121.80),
            io.github.jasondorjeshort.dystopo.areas.AreaPoint(47.20, -121.80), io.github.jasondorjeshort.dystopo.areas.AreaPoint(47.20, -122.10),
        )
        val a = AreaOfInterest(AreaShape.Polygon(listOf(ring), 0.0), 8, 13, "L")
        val box = a.bounds
        val l = Holding("L", Kind.AREA, box, 13, 13, routing = false, cells = { z, x, y -> TileCover.of(a, z).contains(x, y) })
        val inBox = PackBounds(-121.70, 46.95, -121.60, 47.10) // inside the box, outside the L
        val h = HeldHere.held(inBox, Want("less", routing = false), listOf(l))
        assertTrue(h.share < 0.2, "the L's cells hold little of the box's empty corner: ${h.share}")
        assertTrue(h.verdict != Verdict.ALL)
        val inL = PackBounds(-122.00, 46.65, -121.60, 46.85)
        assertEquals(Verdict.ALL, HeldHere.held(inL, Want("less", routing = false), listOf(l)).verdict)
    }

    @Test
    fun aMapAndAnAreaTogether() {
        // The fine map holds the summit; a less-detail area holds the rest of the view: held whole, at less detail.
        val box = PackBounds(-121.70, 46.83, -121.64, 46.87) // straddles the fine map's east edge (-121.68)
        val east = area("east", PackBounds(-121.69, 46.80, -121.60, 46.90), 13)
        val h = HeldHere.held(box, Want("less", routing = false), listOf(fine, east))
        assertEquals(Verdict.ALL, h.verdict)
        assertEquals(listOf("rainier-fine", "east"), h.holders.map { it.name }) // finest first
        val full = HeldHere.held(box, Want("full", routing = true), listOf(fine, east))
        assertEquals(Verdict.PART, full.verdict)
        assertTrue(full.shareAtDetail in 0.1..0.6, "${full.shareAtDetail}")
    }

    @Test
    fun aBoxThatStraddlesAMapsEdgeIsPart() {
        val box = PackBounds(-121.72, 46.85, -121.64, 46.88)
        val h = HeldHere.held(box, Want("full", routing = true), listOf(fine))
        assertEquals(Verdict.PART, h.verdict)
        assertTrue(h.share in 0.3..0.7, "${h.share}")
        assertEquals(h.share, h.shareAtDetail)
        assertNull(h.wholeAtDetail)
    }

    @Test
    fun aMapThatIsOffHoldsNothing() {
        val off = map("rainier-fine", fineBox, 14, 14, off = true)
        val h = HeldHere.held(view, Want("full", routing = true), listOf(off))
        assertEquals(Verdict.NOTHING, h.verdict)
        assertTrue(h.holders.isEmpty())
        assertEquals(listOf("rainier-fine"), h.off.map { it.name })
        assertEquals(1.0, h.off.single().share)
        // With the wide map on, the wide map holds it, coarser; the fine one is still named as off.
        val both = HeldHere.held(view, Want("full", routing = true), listOf(wide, off))
        assertEquals(Verdict.COARSER, both.verdict)
        assertEquals(listOf("rainier-wide"), both.holders.map { it.name })
        assertEquals(listOf("rainier-fine"), both.off.map { it.name })
    }

    @Test
    fun nothingOutsideEveryMap() {
        val h = HeldHere.held(PackBounds(-121.72, 45.98, -121.68, 46.02), Want("less", routing = false), listOf(wide, fine))
        assertEquals(Verdict.NOTHING, h.verdict)
        assertEquals(0.0, h.share)
    }

    @Test
    fun aWideBoxIsCutCoarser() {
        val h = HeldHere.held(PackBounds(-125.0, 42.0, -117.0, 49.0), Want("full", routing = false), listOf(wide), maxCells = 4096)
        assertTrue(h.cells <= 4096 && h.zoom < 14, "${h.cells} cells at z${h.zoom}")
        assertEquals(Verdict.COARSER, h.verdict)
    }
}
