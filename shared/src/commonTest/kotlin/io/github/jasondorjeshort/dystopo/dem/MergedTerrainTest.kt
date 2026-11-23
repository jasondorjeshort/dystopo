package io.github.jasondorjeshort.dystopo.dem

import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T340: [MergedTerrain], the terrain read cell by cell: the finest map, a coarser one where it ends, then the online source. */
class MergedTerrainTest {
    private val z = 12

    /** A window of [size] cells whose middle is ([lat], [lon]). */
    private fun window(lat: Double, lon: Double, size: Int = 64): MercatorWindow {
        val gx = (WebMercator.lonToX(lon, z) * 256).toInt()
        val gy = (WebMercator.latToY(lat, z) * 256).toInt()
        return MercatorWindow(z, gx - size / 2, gy - size / 2, size, size)
    }

    /** The coarse map's box (a public Rainier-sized box), the fine one inside it. */
    private val wideBox = PackBounds(-121.95, 46.68, -121.45, 46.98)
    private val fineBox = PackBounds(-121.82, 46.80, -121.68, 46.90)

    /**
     * A source answering [value] in every cell for which [has] is true (by the cell's centre), NaN elsewhere; [asked] records
     * each window it was asked for.
     */
    private fun source(name: String, maxZoom: Int, box: PackBounds?, value: Float, reach: PackBounds? = box, asked: MutableList<MercatorWindow> = ArrayList(), has: (Double, Double) -> Boolean = { _, _ -> true }) =
        ElevationSource(name, maxZoom, box, reach) { w ->
            asked += w
            ElevationGrid(w.width, w.height, w.cellSizeMeters, FloatArray(w.width * w.height) { k -> if (has(w.latAt(k / w.width), w.lonAt(k % w.width))) value else Float.NaN })
        }

    private fun cellAt(w: MercatorWindow, g: ElevationGrid, lat: Double, lon: Double): Float {
        val i = (WebMercator.lonToX(lon, z) * 256).toInt() - w.x0
        val j = (WebMercator.latToY(lat, z) * 256).toInt() - w.y0
        return g.values[j * w.width + i]
    }

    @Test
    fun theFineMapWinsInsideItAndTheCoarseOneWhereItEnds() {
        // A window over the fine map's south edge: north of it the fine map, south of it the wide one.
        val w = window(46.80, -121.75)
        val t = MergedTerrain.Tally()
        val g = MergedTerrain.read(w, listOf(source("wide", 11, wideBox, 1f), source("fine", 14, fineBox, 2f)), t)
        assertEquals(2f, cellAt(w, g, 46.805, -121.75))
        assertEquals(1f, cellAt(w, g, 46.795, -121.75))
        assertEquals(listOf("fine", "wide"), t.cells.keys.toList())
        assertTrue(t.complete)
    }

    @Test
    fun aCellMissingInTheFineMapIsFilledByTheCoarseOne() {
        val w = window(46.85, -121.76)
        val hole = { lat: Double, lon: Double -> !(lat in 46.849..46.851 && lon in -121.762..-121.758) }
        val t = MergedTerrain.Tally()
        val g = MergedTerrain.read(w, listOf(source("fine", 14, fineBox, 2f, has = hole), source("wide", 11, wideBox, 1f)), t)
        assertEquals(1f, cellAt(w, g, 46.85, -121.76), "the fine map's hole read from the wide map")
        assertEquals(2f, cellAt(w, g, 46.855, -121.75))
        assertTrue((t.cells["wide"] ?: 0) > 0 && (t.cells["fine"] ?: 0) > 0)
    }

    @Test
    fun aCellMissingInBothIsFilledOnlineWhenAllowedAndNoDataWhenNot() {
        val w = window(46.85, -121.76)
        val hole = { lat: Double, lon: Double -> !(lat in 46.849..46.851 && lon in -121.762..-121.758) }
        val maps = listOf(source("fine", 14, fineBox, 2f, has = hole), source("wide", 11, wideBox, 1f, has = hole))
        val online = MergedTerrain.Tally()
        val g = MergedTerrain.read(w, maps + source("net", 14, null, 3f), online)
        assertEquals(3f, cellAt(w, g, 46.85, -121.76))
        assertTrue(online.online && online.complete)
        val offline = MergedTerrain.Tally()
        val g2 = MergedTerrain.read(w, maps, offline)
        assertTrue(cellAt(w, g2, 46.85, -121.76).isNaN())
        assertFalse(offline.online)
        assertTrue(offline.missing > 0 && !offline.complete)
    }

    @Test
    fun aCoarsePrimaryMapListedFirstDoesNotWinInsideTheFineOne() {
        // The caller's list puts the wide map first (as PackPrecedence does for a primary map); the read orders by depth.
        val w = window(46.85, -121.76)
        val g = MergedTerrain.read(w, listOf(source("wide-primary", 11, wideBox, 1f), source("fine", 14, fineBox, 2f)))
        assertTrue(g.values.all { it == 2f })
        assertEquals(listOf("fine", "wide-primary"), MergedTerrain.order(listOf(source("wide-primary", 11, wideBox, 1f), source("fine", 14, fineBox, 2f), source("net", 15, null, 3f))).map { it.name }.take(2))
        assertEquals("net", MergedTerrain.order(listOf(source("net", 15, null, 3f), source("wide", 11, wideBox, 1f))).last().name, "the online source is last whatever its depth")
    }

    @Test
    fun aWindowInsideTheFineMapNeverAsksTheCoarseMapOrTheNetwork() {
        val w = window(46.85, -121.76, size = 32)
        val wideAsked = ArrayList<MercatorWindow>()
        val netAsked = ArrayList<MercatorWindow>()
        MergedTerrain.read(w, listOf(source("fine", 14, fineBox, 2f), source("wide", 11, wideBox, 1f, asked = wideAsked), source("net", 14, null, 3f, asked = netAsked)))
        assertTrue(wideAsked.isEmpty() && netAsked.isEmpty())
    }

    @Test
    fun theNetworkIsAskedOnlyForTheRectangleStillMissing() {
        // Over the wide map's south edge: the network is asked for the rows south of it only.
        val w = window(46.68, -121.70)
        val netAsked = ArrayList<MercatorWindow>()
        val t = MergedTerrain.Tally()
        val g = MergedTerrain.read(w, listOf(source("wide", 11, wideBox, 1f), source("net", 14, null, 3f, asked = netAsked)), t)
        assertEquals(1, netAsked.size)
        assertTrue(netAsked[0].height < w.height && netAsked[0].width == w.width)
        assertEquals(3f, cellAt(w, g, 46.675, -121.70))
        assertEquals(1f, cellAt(w, g, 46.685, -121.70))
        assertEquals(listOf("wide", MergedTerrain.ONLINE), t.cells.keys.toList())
    }

    @Test
    fun aMapsWholeTilesPastItsBoxAnswerOfflineButAfterTheNetworkAndAfterABoxThatHoldsTheCell() {
        val reach = MergedTerrain.wholeTileReach(fineBox, 8)
        assertTrue(reach.minLat < 46.2 && reach.maxLat > 47.0, "the fine map's z8 tile reaches past its box: $reach")
        // Just south of the fine map, inside the wide one: the wide map's box wins over the fine map's margin.
        val w = window(46.79, -121.75)
        val g = MergedTerrain.read(w, listOf(source("fine", 14, fineBox, 2f, reach = reach), source("wide", 11, wideBox, 1f)))
        assertEquals(1f, cellAt(w, g, 46.785, -121.75))
        // Outside both boxes: the network first, then the margin when there is no network.
        val far = window(46.5, -121.75)
        assertTrue(MergedTerrain.read(far, listOf(source("fine", 14, fineBox, 2f, reach = reach), source("net", 14, null, 3f))).values.all { it == 3f })
        assertTrue(MergedTerrain.read(far, listOf(source("fine", 14, fineBox, 2f, reach = reach))).values.all { it == 2f })
    }

    @Test
    fun thePointsZoomIsTheDeepestMapHoldingIt() {
        val sources = listOf(source("wide", 11, wideBox, 1f), source("fine", 14, fineBox, 2f, reach = MergedTerrain.wholeTileReach(fineBox, 8)), source("net", 15, null, 3f))
        assertEquals(14, MergedTerrain.pointZoom(sources, 46.85, -121.76, 14))
        assertEquals(11, MergedTerrain.pointZoom(sources, 46.73, -121.56, 14), "inside the wide map only")
        assertEquals(14, MergedTerrain.pointZoom(sources, 46.0, -121.7, 14), "outside both: the online source's, capped")
        assertEquals(14, MergedTerrain.pointZoom(sources.take(2), 46.5, -121.75, 14), "past the boxes, inside the fine map's tiles")
        assertNull(MergedTerrain.pointZoom(sources.take(2), 40.0, -100.0, 14))
    }
}
