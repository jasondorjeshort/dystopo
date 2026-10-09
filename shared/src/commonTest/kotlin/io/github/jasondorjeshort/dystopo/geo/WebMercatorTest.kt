package io.github.jasondorjeshort.dystopo.geo

import kotlin.test.Test
import kotlin.test.assertEquals

class WebMercatorTest {
    @Test
    fun worldCornersAndCenter() {
        assertEquals(0.0, WebMercator.lonToX(-180.0, 3), 1e-9)
        assertEquals(8.0, WebMercator.lonToX(180.0, 3), 1e-9)
        assertEquals(4.0, WebMercator.latToY(0.0, 3), 1e-9)
        assertEquals(0.0, WebMercator.latToY(WebMercator.MAX_LAT, 3), 1e-6)
    }

    @Test
    fun roundTrip() {
        for (zoom in listOf(0, 7, 15)) {
            for ((lat, lon) in listOf(46.85 to -121.76, 45.83 to 6.86, -33.9 to 151.2)) {
                assertEquals(lon, WebMercator.xToLon(WebMercator.lonToX(lon, zoom), zoom), 1e-9)
                assertEquals(lat, WebMercator.yToLat(WebMercator.latToY(lat, zoom), zoom), 1e-9)
            }
        }
    }

    @Test
    fun metersPerPixelAtEquator() {
        assertEquals(156_543.03, WebMercator.metersPerPixel(0.0, 0), 0.01)
        assertEquals(156_543.03 / 1024, WebMercator.metersPerPixel(0.0, 10), 0.001)
    }

    @Test
    fun tilesCoveringSpansAllTouchedTiles() {
        // A box straddling the corner of four zoom-1 tiles.
        val tiles = WebMercator.tilesCovering(BoundingBox(-10.0, -10.0, 10.0, 10.0), 1)
        assertEquals(setOf(TileId(1, 0, 0), TileId(1, 1, 0), TileId(1, 0, 1), TileId(1, 1, 1)), tiles.toSet())
        // A tiny box inside one tile.
        assertEquals(1, WebMercator.tilesCovering(BoundingBox(6.86, 45.83, 6.861, 45.831), 12).size)
    }
}
