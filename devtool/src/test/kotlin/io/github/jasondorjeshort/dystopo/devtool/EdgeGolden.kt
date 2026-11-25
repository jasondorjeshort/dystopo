package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.dem.EdgeBlend
import io.github.jasondorjeshort.dystopo.dem.MergedTile
import io.github.jasondorjeshort.dystopo.dem.RawElevations
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import java.awt.image.BufferedImage
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * T357 (ADR 0066 decision 4): the edge join's goldens (`goldens/edge/`). Two synthetic maps over the two-maps fixture's boxes
 * (public Rainier): a fine one storing z8 to z14 inside the fine box, with small ridges, and a wide one storing z8 to z11, the
 * same ground smoothed and 20 m higher (another survey), so any seam shows. Each golden is 2 x 2 tiles of [MergedTile]'s
 * composition at z12, z14 and z15, read tile by tile through its pyramid as the phone's contour and shade paths read it, the
 * window's middle on a tile corner beside the fine map's south edge (46.80 N), drawn by the map renderer (hillshade and
 * contours). What to see: no step on the tile boundaries, no seam along the box (the 20 m come in over [EdgeBlend.BAND_M]).
 */
object EdgeGolden {
    class Case(val file: String, val z: Int)

    val cases = listOf(Case("edge/fine-edge-z12.png", 12), Case("edge/fine-edge-z14.png", 14), Case("edge/fine-edge-z15.png", 15))

    private val wideBox = PackBounds(-121.95, 46.68, -121.45, 46.98)
    private val fineBox = PackBounds(-121.82, 46.80, -121.68, 46.90)

    private fun smooth(lon: Double, lat: Double): Double = 1400 + 250 * sin(lon * 90) * cos(lat * 110)
    private fun ridges(lon: Double, lat: Double): Double = 18 * sin(lon * 2400 + lat * 900) * sin(lat * 1700)

    private fun pyramid(minZ: Int, maxZ: Int, f: (Double, Double) -> Double) = DemPyramid(minZ, maxZ, RawElevations, cacheTiles = 64) { z, x, y ->
        RawElevations.encode(FloatArray(256 * 256) { k -> f(WebMercator.xToLon(x + (k % 256 + 0.5) / 256, z), WebMercator.yToLat(y + (k / 256 + 0.5) / 256, z)).toFloat() })
    }

    fun merged(): MergedTile = MergedTile(
        listOf(
            MergedTile.Source("wide", "0000000a", wideBox, 8, 11, pyramid(8, 11) { lon, lat -> smooth(lon, lat) + 20 }, chosen = true),
            MergedTile.Source("fine", "0000000f", fineBox, 8, 14, pyramid(8, 14) { lon, lat -> smooth(lon, lat) + ridges(lon, lat) }),
        ),
    )

    fun render(case: Case): BufferedImage {
        val m = merged()
        val z = case.z
        // The tile corner nearest the fine map's south edge at -121.75 (the edge inside the window at every zoom).
        val cx = floor(WebMercator.lonToX(-121.75, z) + 0.5).toInt()
        val cy = floor(WebMercator.latToY(fineBox.minLat, z) + 0.5).toInt()
        val grid = if (z <= m.pyramid.maxZoom) m.pyramid.window(z, (cx - 1) * 256, (cy - 1) * 256, 512, 512) else m.window(z, (cx - 1) * 256, (cy - 1) * 256, 512, 512)
        return MapRenderer.render(grid, z, styleOptions(), Timings()).image
    }
}
