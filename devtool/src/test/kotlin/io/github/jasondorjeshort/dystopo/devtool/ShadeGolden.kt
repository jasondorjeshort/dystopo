package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.dem.Terrain
import io.github.jasondorjeshort.dystopo.dem.TerrainLayer
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.render.CastShadow
import io.github.jasondorjeshort.dystopo.testing.SyntheticTerrain
import java.awt.image.BufferedImage
import kotlin.math.floor

/**
 * T181: the sun-and-shade goldens (`goldens/shade/`): the base map with the terrain's shade in the shade tiles' cool
 * tint ([Shading.sunShade] over [CastShadow]), each from one layer of terrain (rays stop at its edge). The synthetic
 * cone at z12 (the whole cone, 900 m on a 2 km radius, and its shadow past the foot) under a fixed sun from the south-east, 15 degrees up; Mount Rainier's summit tile ([DemFixture.RAINIER])
 * under the real sun of 2026-10-30 16:00 UTC (a [CastShadow.SunField], as the tiles march).
 */
object ShadeGolden {
    class Case(val file: String, val render: () -> BufferedImage)

    /** 2026-10-30T16:00Z, the task's instant: mid-morning at Rainier, the sun low in the south-east. */
    const val RAINIER_MS = 1_793_376_000_000L

    val cases = listOf(
        Case("shade/cone-az135-alt15-z12.png") { cone() },
        Case("shade/rainier-20261030T1600Z-z12.png") { rainier() },
    )

    private fun image(w: Int, h: Int, pixels: IntArray) = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { it.setRGB(0, 0, w, h, pixels, 0, w) }

    private fun cone(): BufferedImage {
        val zoom = 12
        val size = 256
        // Placed at 45 N, 0.5 E (as the OSM golden), the cell size Web Mercator has there.
        val gx = floor(WebMercator.lonToX(0.5, zoom) * 256).toInt()
        val gy = floor(WebMercator.latToY(45.0, zoom) * 256).toInt()
        val window = MercatorWindow(zoom, gx - size / 2, gy - size / 2, size, size)
        val grid = SyntheticTerrain.CONE.grid(size, size, WebMercator.metersPerPixel(45.0, zoom))
        val r = CastShadow.compute(Terrain(listOf(TerrainLayer(window, grid))), window, 135.0, 15.0)
        return image(size, size, Shading.sunShade(grid, r.shade))
    }

    private fun rainier(): BufferedImage {
        val f = DemFixture.RAINIER
        val window = MercatorWindow(f.z, f.x * 256, f.y * 256, 256, 256)
        val grid = f.grid()
        val r = CastShadow.at(Terrain(listOf(TerrainLayer(window, grid))), window, RAINIER_MS)
        return image(256, 256, Shading.sunShade(grid, r.shade))
    }
}
