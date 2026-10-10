package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.dem.Composite
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwissAltiSourceTest {
    @Test
    fun parsesStacItems() {
        // Trimmed from a real items page (two survey years of one tile, plus a tile with only xyz assets).
        val page = Json.parseToJsonElement(
            """
            {"type":"FeatureCollection","features":[
              {"id":"swissalti3d_2019_2617-1091","assets":{
                "swissalti3d_2019_2617-1091_0.5_2056_5728.tif":{"href":"https://example/a05.tif"},
                "swissalti3d_2019_2617-1091_0.5_2056_5728.xyz.zip":{"href":"https://example/a05.zip"},
                "swissalti3d_2019_2617-1091_2_2056_5728.tif":{"href":"https://example/a2.tif"}}},
              {"id":"swissalti3d_2024_2617-1091","assets":{
                "swissalti3d_2024_2617-1091_2_2056_5728.tif":{"href":"https://example/b2.tif"}}},
              {"id":"swissalti3d_2019_2618-1091","assets":{
                "swissalti3d_2019_2618-1091_2_2056_5728.xyz.zip":{"href":"https://example/c.zip"}}}
            ],"links":[]}
            """,
        ).jsonObject
        val tiles = SwissAltiSource.parseItems(page)
        assertEquals(2, tiles.size)
        assertEquals(SwissAltiSource.Tile(2617, 1091, 2019, "https://example/a2.tif", "https://example/a05.tif", 5728), tiles[0])
        assertEquals(SwissAltiSource.Tile(2617, 1091, 2024, "https://example/b2.tif", null, 5728), tiles[1])
    }

    @Test
    fun coverageGateAndIndexCells() {
        assertTrue(SwissAltiSource.intersectsCoverage(7.62, 45.95, 7.70, 46.00)) // Matterhorn
        assertFalse(SwissAltiSource.intersectsCoverage(-121.86, 46.78, -121.66, 46.93)) // Rainier
        assertEquals(emptyList(), SwissAltiSource.cellsFor(-121.86, 46.78, -121.66, 46.93))
        assertEquals(listOf(459 to 76, 460 to 76), SwissAltiSource.cellsFor(7.62, 45.95, 7.69, 46.01))
    }

    @Test
    fun outsideCoverageFetchesNothing() {
        val window = MercatorWindow.forBbox(BoundingBox(-121.86, 46.78, -121.66, 46.93), 13)
        val r = SwissAltiSource(kotlin.io.path.createTempDirectory("swiss").toFile()).fetch(window)
        assertEquals(1L, r.counters["outsideCoverage"])
        assertTrue(r.grid.values.all { it.isNaN() })
        assertEquals(emptyList(), r.provenance)
    }

    @Test
    fun seamStepIsSmallWhenFeatheredAndLargeWhenCut() {
        // Left half covered 20 m above the fallback; a hard cut steps 20 m, a 10-cell feather ~nothing.
        val w = 40
        val h = 4
        val fallback = ElevationGrid(w, h, 10.0, FloatArray(w * h) { 1000f })
        val top = ElevationGrid(w, h, 10.0, FloatArray(w * h) { if (it % w < 20) 1020f else Float.NaN })
        val (feathered, hard) = SwissAltiSource.seamSteps(top, fallback, Composite.over(top, fallback, 10.0))!!
        assertEquals(20.0, hard, 1e-6)
        assertTrue(feathered < 1.0, "feathered step $feathered")
        val (cut, _) = SwissAltiSource.seamSteps(top, fallback, Composite.over(top, fallback, 0.0))!!
        assertEquals(20.0, cut, 1e-6)
        assertNull(SwissAltiSource.seamSteps(fallback, fallback, fallback))
    }
}
