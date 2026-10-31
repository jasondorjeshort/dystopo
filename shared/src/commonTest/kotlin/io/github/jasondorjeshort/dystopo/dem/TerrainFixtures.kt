package io.github.jasondorjeshort.dystopo.dem

import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import kotlin.math.floor

/**
 * T181: synthetic terrain placed on the Web Mercator world for the horizon and cast-shadow tests: a height function of
 * ground metres east and north of ([lat], [lon]), sampled into windows at any zoom with the scale at that point (the
 * scale [RayMarch] uses there), so a test knows exactly what a ray should meet.
 */
class TerrainFixture(val lat: Double, val lon: Double, val heightAt: (east: Double, north: Double) -> Double) {
    fun gx(zoom: Int): Double = WebMercator.lonToX(lon, zoom) * 256
    fun gy(zoom: Int): Double = WebMercator.latToY(lat, zoom) * 256

    /** Metres per pixel at the point. */
    fun mpp(zoom: Int): Double = WebMercator.metersPerPixel(lat, zoom)

    /** A square window of [radius] cells around the point at [zoom]. */
    fun window(zoom: Int, radius: Int): MercatorWindow =
        MercatorWindow(zoom, floor(gx(zoom)).toInt() - radius, floor(gy(zoom)).toInt() - radius, 2 * radius + 1, 2 * radius + 1)

    /** The grid of [window], each cell the height at its centre. */
    fun grid(window: MercatorWindow): ElevationGrid {
        val z = window.zoom
        val m = mpp(z)
        val cx = gx(z)
        val cy = gy(z)
        val values = FloatArray(window.width * window.height) { k ->
            val i = k % window.width
            val j = k / window.width
            heightAt((window.x0 + i + 0.5 - cx) * m, -(window.y0 + j + 0.5 - cy) * m).toFloat()
        }
        return ElevationGrid(window.width, window.height, m, values)
    }

    /** A terrain read from [windows] (with bands), as a plan would ask. */
    fun terrain(windows: List<Pair<MercatorWindow, Double>>): Terrain = Terrain.read(windows) { grid(it) }

    /** Global pixel of a point [east], [north] metres from the centre at [zoom]. */
    fun at(zoom: Int, east: Double, north: Double): Pair<Double, Double> = (gx(zoom) + east / mpp(zoom)) to (gy(zoom) - north / mpp(zoom))
}
