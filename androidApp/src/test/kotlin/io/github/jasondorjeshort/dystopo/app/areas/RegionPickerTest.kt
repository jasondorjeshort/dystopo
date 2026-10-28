package io.github.jasondorjeshort.dystopo.app.areas

import io.github.jasondorjeshort.dystopo.app.online.RegionDownload
import io.github.jasondorjeshort.dystopo.areas.AreaManifest
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T153: "Download this region" through the picker's size-first summary: the detail chosen for the view, the pack path kept
 * within the server's build limits and the area path past them, the line that says which, the pack name from the name field.
 */
class RegionPickerTest {
    /** A z12 phone view at Paradise (Rainier): about 0.07 by 0.1 degrees. */
    private val cityView = PackBounds(-121.77, 46.74, -121.70, 46.84)
    /** A z6 phone view over Washington: about 4.5 by 6.7 degrees. */
    private val stateView = PackBounds(-122.9, 44.0, -118.4, 50.7)

    @Test
    fun theViewsZoomChoosesTheDetail() {
        assertEquals(DETAIL_FULL, regionDetail(12.0), "a city view opens at full detail")
        assertEquals(DETAIL_FULL, regionDetail(REGION_FULL_FROM_ZOOM))
        assertEquals(DETAIL_LESS, regionDetail(9.9))
        assertEquals(DETAIL_LESS, regionDetail(6.0), "a state view opens at less detail")
    }

    @Test
    fun aBoxWithinTheServersLimitsKeepsThePackPath() {
        assertTrue(RegionDownload.packFits(cityView))
        assertTrue(RegionDownload.packFits(PackBounds(-122.0, 46.5, -121.0, 47.5)), "a 1 degree box is about 8,000 tiles at z8 to z14")
        assertFalse(RegionDownload.packFits(stateView), "over 3 degrees a side")
        val wide = PackBounds(-122.5, 46.0, -120.0, 48.5)
        assertTrue(wide.maxLon - wide.minLon <= RegionDownload.MAX_SPAN_DEG && wide.maxLat - wide.minLat <= RegionDownload.MAX_SPAN_DEG)
        assertTrue(RegionDownload.tileCount(wide, 8..14) > RegionDownload.TILE_LIMIT)
        assertFalse(RegionDownload.packFits(wide), "within the span but over the tile limit at z14: the area path, not a lowered top zoom")
        assertFalse(RegionDownload.packFits(PackBounds(170.0, 10.0, -170.0, 11.0)), "across the antimeridian")
    }

    @Test
    fun thePathLineSaysWhichPathAndWhy() {
        assertEquals(REGION_PACK_LINE, regionPathLine(packFits = true, detail = DETAIL_FULL))
        assertEquals("Tiles only: routing needs a smaller box", regionPathLine(packFits = false, detail = DETAIL_FULL))
        assertEquals(REGION_LESS_LINE, regionPathLine(packFits = true, detail = DETAIL_LESS))
        assertEquals(REGION_LESS_LINE, regionPathLine(packFits = false, detail = DETAIL_LESS))
    }

    @Test
    fun theRegionIsTheBoxAsAPolygonWithNoSpanCap() {
        val full = regionArea(stateView, AreaPicker.FULL_MAX_ZOOM, "view-2026-10-27-1")
        val less = regionArea(stateView, AreaPicker.LESS_MAX_ZOOM, null)
        assertTrue(full.shape is AreaShape.Polygon, "a polygon: AreaDownload plans it (a box would be the pack path)")
        assertEquals(8 to 15, full.minZoom to full.maxZoom)
        assertEquals(8 to 13, less.minZoom to less.maxZoom)
        assertEquals("view-2026-10-27-1", full.name)
        val b = full.bounds
        assertEquals(stateView.minLon, b.minLon, 1e-9)
        assertEquals(stateView.maxLat, b.maxLat, 1e-9)
        // The estimate the summary shows (T152's rows): less detail is a small share of full detail over a state.
        val fullTiles = AreaManifest.tileItems(full, 6..15, 8..14).sumOf { it.count.toLong() }
        val lessTiles = AreaManifest.tileItems(less, 6..13, 8..13).sumOf { it.count.toLong() }
        assertTrue(lessTiles in 1 until fullTiles / 5, "$lessTiles less, $fullTiles full")
        assertFailsWith<IllegalArgumentException> { regionRing(PackBounds(170.0, 10.0, -170.0, 11.0)) }
    }

    @Test
    fun theBboxQueryReadsFourNumbers() {
        assertEquals(PackBounds(-121.8, 46.7, -121.7, 46.9), regionBox("-121.8, 46.7,-121.7,46.9"))
        assertFailsWith<IllegalArgumentException> { regionBox("1,2,3") }
        assertFailsWith<IllegalArgumentException> { regionBox("a,b,c,d") }
    }

    @Test
    fun aPackNameComesFromTheNameField() {
        assertEquals("view-2026-10-27-1", RegionDownload.packNameFrom("view-2026-10-27-1", "x"))
        assertEquals("Mount-Rainier-2", RegionDownload.packNameFrom("Mount Rainier 2", "x"))
        assertEquals("Paradise", RegionDownload.packNameFrom("  Paradise  ", "x"))
        assertEquals("Greenwich-park", RegionDownload.packNameFrom("Greenwich / park!", "x"))
        assertEquals("fallback-1", RegionDownload.packNameFrom("", "fallback-1"))
        assertEquals("fallback-1", RegionDownload.packNameFrom("»«", "fallback-1"))
        assertEquals("a.b", RegionDownload.packNameFrom("a...b", "x"), "no '..' in a pack name")
        assertEquals(64, RegionDownload.packNameFrom("x".repeat(80) + " y", "f").length)
    }
}
