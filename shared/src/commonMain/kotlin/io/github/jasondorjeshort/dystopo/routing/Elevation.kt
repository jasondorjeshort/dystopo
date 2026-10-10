package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import kotlin.math.floor

/** Elevation in metres (EGM2008) at a point, NaN where there is no data. */
fun interface ElevationSampler {
    fun elevation(lat: Double, lon: Double): Double
}

/**
 * Samples a DEM pack bilinearly at [zoom] (pixel centres as in [DemPyramid]); ancestors fill where
 * the pack has no tile at that zoom. Zoom 13 (about 13 m pixels at Rainier's latitude) is plenty for
 * grades along a trail and decodes a quarter of the tiles zoom 14 would.
 */
class DemElevation(private val pyramid: DemPyramid, val zoom: Int = minOf(pyramid.maxZoom, 13)) : ElevationSampler {
    var samples: Long = 0
        private set

    override fun elevation(lat: Double, lon: Double): Double {
        samples++
        val ts = pyramid.tileSize
        val fx = WebMercator.lonToX(lon, zoom) * ts - 0.5
        val fy = WebMercator.latToY(lat, zoom) * ts - 0.5
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        val a = pyramid.elevation(zoom, x0, y0)
        val b = pyramid.elevation(zoom, x0 + 1, y0)
        val c = pyramid.elevation(zoom, x0, y0 + 1)
        val d = pyramid.elevation(zoom, x0 + 1, y0 + 1)
        if (a.isNaN() || b.isNaN() || c.isNaN() || d.isNaN()) {
            // At the pack edge: the nearest of the four that has a value.
            val near = when {
                tx < 0.5 && ty < 0.5 -> a
                tx >= 0.5 && ty < 0.5 -> b
                tx < 0.5 -> c
                else -> d
            }
            return near.toDouble()
        }
        return ((a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty)
    }
}
