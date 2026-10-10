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

    @Test
    fun antimeridianBoxIsUnwrapped() {
        val box = BoundingBox.unwrapped(179.8, -17.0, -179.8, -16.8)
        assertEquals(179.8, box.west, 1e-12)
        assertEquals(180.2, box.east, 1e-9)
        assertEquals(180.0, box.centerLon, 1e-9)
        // An ordinary box is untouched.
        assertEquals(BoundingBox(6.0, 45.0, 7.0, 46.0), BoundingBox.unwrapped(6.0, 45.0, 7.0, 46.0))
        // The window is one continuous strip whose pixel longitudes run past 180.
        val window = MercatorWindow.forBbox(box, 10)
        assertEquals(true, window.lonAt(0) < 180.0)
        assertEquals(true, window.lonAt(window.width - 1) > 180.0)
        // Tiles continue from the last column into column 0 on the other side.
        assertEquals(listOf(TileId(8, 255, 140), TileId(8, 0, 140)), WebMercator.tilesCovering(box, 8))
        // A box ending exactly at 180 stays in the last column.
        assertEquals(listOf(TileId(8, 255, 140)), WebMercator.tilesCovering(BoundingBox(179.8, -17.0, 180.0, -16.8), 8))
    }
}
