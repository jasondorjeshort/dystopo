package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import javax.imageio.ImageIO

/**
 * T104: one real DEM tile kept as a test resource (`src/test/resources/fixtures/`, provenance in the README
 * there), so a golden can show real terrain offline: a 256 px Terrarium PNG, tile [z]/[x]/[y], read at its own
 * Web Mercator cell size (the latitude of the tile's centre), elevations in metres as the pack stores them.
 */
class DemFixture(val name: String, val z: Int, val x: Int, val y: Int) {
    val resource: String get() = "/fixtures/$name-dem-$z-$x-$y.png"

    /** Ground metres per pixel at the tile's centre latitude. */
    val cellSizeMeters: Double get() = WebMercator.metersPerPixel(WebMercator.yToLat(y + 0.5, z), z)

    fun grid(): ElevationGrid {
        val image = (DemFixture::class.java.getResourceAsStream(resource) ?: error("no fixture $resource")).use { ImageIO.read(it) }
        val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        return Terrarium.decode(argb, image.width, image.height, cellSizeMeters)
    }

    companion object {
        /** Mount Rainier's summit tile at z12 (`areas.json` rainier): summit, glaciers, cliffs and valley floors. */
        val RAINIER = DemFixture("rainier", 12, 662, 1443)
    }
}
