package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.packs.PackNames
import io.github.jasondorjeshort.dystopo.tiles.ContourTiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnlinePlanTest {
    @Test
    fun demTilesForAContourTileCoverItsWindow() {
        // A z12 contour tile: 512 px at its DEM zoom plus a halo, so it needs a 3x3 (or 2x2) block of 256-px tiles.
        val plan = ContourTiles.plan(12, 2130, 1453)
        val tiles = OnlineTiles.demTilesFor(plan.sampleZoom, plan.windowX, plan.windowY, plan.windowSize)
        assertTrue(tiles.all { it.first == plan.sampleZoom.coerceIn(OnlineTiles.DEM_MIN_ZOOM, OnlineTiles.DEM_MAX_ZOOM) })
        assertTrue(tiles.size in 4..9, "got ${tiles.size}")
        val xs = tiles.map { it.second }
        assertTrue(plan.windowX / 256 in xs && (plan.windowX + plan.windowSize - 1) / 256 in xs)
    }

    @Test
    fun deepWindowsReadTheDeepestServerZoom() {
        // z17 pixels map onto z15 tiles (overzoom): a 512-px window is under one or two z15 tiles per axis.
        val tiles = OnlineTiles.demTilesFor(17, 1024 * 4, 1024 * 4, 512)
        assertTrue(tiles.all { it.first == 15 })
        assertEquals(listOf(Triple(15, 4, 4)), tiles)
        // z4 windows average z6.
        assertTrue(OnlineTiles.demTilesFor(4, 0, 0, 256).all { it.first == 6 })
        assertEquals(16, OnlineTiles.demTilesFor(4, 0, 0, 256).size)
    }

    @Test
    fun regionNamesAreValidPackNames() {
        val box = PackBounds(6.80, 45.80, 6.93, 45.88)
        val name = RegionDownload.nameFor(box, 8..14)
        assertEquals("view-45.84n-6.87e-z8-14", name)
        assertTrue(PackNames.isValidName(name))
        assertTrue(PackNames.isValidName(RegionDownload.nameFor(PackBounds(-121.9, 46.7, -121.6, 46.9), 8..13)))
    }

    @Test
    fun tileCountMatchesTheServersRule() {
        val box = PackBounds(6.80, 45.80, 6.93, 45.87)
        // DEM from z6, OSM to z14: z8..z13 counts each zoom twice.
        val one = RegionDownload.tileCount(box, 8..8)
        assertEquals(2, one, "a small box is one tile at z8, DEM and OSM")
        assertTrue(RegionDownload.tileCount(box, 8..14) < RegionDownload.TILE_LIMIT)
    }

    @Test
    fun serverSettingIsNormalized() {
        assertEquals("", AppSettings.normalizeServer("  "))
        assertEquals("http://10.0.2.2:8765", AppSettings.normalizeServer("http://10.0.2.2:8765/"))
        assertEquals("https://tiles.example.org/dystopo", AppSettings.normalizeServer("https://tiles.example.org/dystopo"))
        assertNull(AppSettings.normalizeServer("ftp://x"))
        assertNull(AppSettings.normalizeServer("10.0.2.2:8765"))
        assertNull(AppSettings.normalizeServer("http://x/\"><script>"))
    }
}
