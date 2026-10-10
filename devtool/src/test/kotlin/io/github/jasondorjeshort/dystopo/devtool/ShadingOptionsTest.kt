package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.testing.SyntheticTerrain
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** `--smooth-dem` touches shading only, and is off by default. */
class ShadingOptionsTest {
    private val case = GoldenCase(SyntheticTerrain.MIXED, zoom = 13)

    @Test
    fun smoothDemIsOffByDefault() {
        val grid = case.grid()
        val (default, stats) = Shading.render(grid, styleOptions(), Timings())
        val (explicitZero, _) = Shading.render(grid, styleOptions("--smooth-dem", "0"), Timings())
        assertContentEquals(default, explicitZero)
        assertNull(stats.smoothDemRadius)
    }

    @Test
    fun smoothDemChangesShadingButNotContours() {
        val grid = case.grid()
        val raw = MapRenderer.render(grid, case.zoom, styleOptions("--slope-overlay"), Timings())
        val smoothed = MapRenderer.render(grid, case.zoom, styleOptions("--slope-overlay", "--smooth-dem", "1"), Timings())
        assertEquals(1, smoothed.shading.smoothDemRadius)
        assertEquals(raw.contours, smoothed.contours, "contours are traced on the raw grid")
        val a = raw.image.getRGB(0, 0, raw.image.width, raw.image.height, null, 0, raw.image.width)
        val b = smoothed.image.getRGB(0, 0, smoothed.image.width, smoothed.image.height, null, 0, smoothed.image.width)
        assertFalse(a.contentEquals(b), "smoothing should change the shading")
    }
}
