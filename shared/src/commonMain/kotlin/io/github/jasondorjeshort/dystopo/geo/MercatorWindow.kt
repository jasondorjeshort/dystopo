package io.github.jasondorjeshort.dystopo.geo

import kotlin.math.ceil
import kotlin.math.floor

/**
 * A pixel-aligned rectangle of the Web Mercator world at [zoom], in global pixel coordinates
 * ([tileSize]-pixel tiles). Every elevation source renders into the same window, so outputs
 * from different sources line up pixel for pixel.
 */
data class MercatorWindow(
    val zoom: Int,
    val x0: Int,
    val y0: Int,
    val width: Int,
    val height: Int,
    val tileSize: Int = 256,
) {
    /** Longitude of the center of column [i]. */
    fun lonAt(i: Int): Double = WebMercator.xToLon((x0 + i + 0.5) / tileSize, zoom)

    /** Latitude of the center of row [j]. */
    fun latAt(j: Int): Double = WebMercator.yToLat((y0 + j + 0.5) / tileSize, zoom)

    /** Approximate pixel size on the ground, taken at the window's middle row. */
    val cellSizeMeters: Double get() = WebMercator.metersPerPixel(latAt(height / 2), zoom, tileSize)

    /** Tiles that overlap the window. */
    fun tiles(): List<TileId> {
        val tx0 = x0 / tileSize
        val tx1 = (x0 + width - 1) / tileSize
        val ty0 = y0 / tileSize
        val ty1 = (y0 + height - 1) / tileSize
        return (ty0..ty1).flatMap { y -> (tx0..tx1).map { x -> TileId(zoom, x, y) } }
    }

    companion object {
        fun forBbox(bbox: BoundingBox, zoom: Int, tileSize: Int = 256): MercatorWindow {
            val px0 = floor(WebMercator.lonToX(bbox.west, zoom) * tileSize).toInt()
            val px1 = ceil(WebMercator.lonToX(bbox.east, zoom) * tileSize).toInt()
            val py0 = floor(WebMercator.latToY(bbox.north, zoom) * tileSize).toInt()
            val py1 = ceil(WebMercator.latToY(bbox.south, zoom) * tileSize).toInt()
            return MercatorWindow(zoom, px0, py0, px1 - px0, py1 - py0, tileSize)
        }
    }
}
