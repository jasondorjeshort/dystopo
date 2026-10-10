package io.github.jasondorjeshort.dystopo.geo

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Geographic bounding box in degrees (WGS84). Always west < east: a box crossing the antimeridian
 * is stored unwrapped, with [east] past 180° (see [unwrapped]); consumers wrap longitudes as needed.
 */
data class BoundingBox(val west: Double, val south: Double, val east: Double, val north: Double) {
    init {
        require(west < east) { "west ($west) must be < east ($east)" }
        require(south < north) { "south ($south) must be < north ($north)" }
    }

    val centerLat: Double get() = (south + north) / 2
    val centerLon: Double get() = (west + east) / 2

    companion object {
        /**
         * A box written the GeoJSON way, where west > east means it crosses the antimeridian
         * (e.g. 179.8..-179.8), unwrapped so east is past 180° (179.8..180.2).
         */
        fun unwrapped(west: Double, south: Double, east: Double, north: Double): BoundingBox =
            BoundingBox(west, south, if (west > east) east + 360.0 else east, north)
    }
}

/** Slippy-map tile address (XYZ scheme, y=0 at the north edge). */
data class TileId(val z: Int, val x: Int, val y: Int) {
    override fun toString() = "$z/$x/$y"
}

/**
 * Web Mercator (EPSG:3857) math in "tile space": at zoom z the world is 2^z tiles wide,
 * x grows eastward from -180°, y grows southward from ~85.05°N.
 */
object WebMercator {
    const val EARTH_CIRCUMFERENCE_M = 40_075_016.686
    const val MAX_LAT = 85.05112878

    private fun worldSize(zoom: Int): Double = (1L shl zoom).toDouble()

    fun lonToX(lon: Double, zoom: Int): Double = (lon + 180.0) / 360.0 * worldSize(zoom)

    fun latToY(lat: Double, zoom: Int): Double {
        val r = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * worldSize(zoom)
    }

    fun xToLon(x: Double, zoom: Int): Double = x / worldSize(zoom) * 360.0 - 180.0

    fun yToLat(y: Double, zoom: Int): Double =
        atan(sinh(PI * (1.0 - 2.0 * y / worldSize(zoom)))) * 180.0 / PI

    /** Ground distance covered by one pixel at [lat], for tiles [tileSize] pixels wide. */
    fun metersPerPixel(lat: Double, zoom: Int, tileSize: Int = 256): Double =
        EARTH_CIRCUMFERENCE_M * cos(lat * PI / 180.0) / (tileSize * worldSize(zoom))

    /**
     * All tiles at [zoom] that intersect [bbox], row by row from the north-west corner. A box
     * unwrapped across the antimeridian (east > 180) continues with tile columns 0, 1... after the
     * last column.
     */
    fun tilesCovering(bbox: BoundingBox, zoom: Int): List<TileId> {
        val n = 1 shl zoom
        val max = n - 1
        val x0 = floor(lonToX(bbox.west, zoom)).toInt().coerceIn(0, max)
        val x1Raw = floor(lonToX(bbox.east, zoom)).toInt()
        val x1 = if (bbox.east <= 180.0) x1Raw.coerceIn(0, max) else x1Raw.coerceAtMost(x0 + max)
        val y0 = floor(latToY(bbox.north, zoom)).toInt().coerceIn(0, max)
        val y1 = floor(latToY(bbox.south, zoom)).toInt().coerceIn(0, max)
        return (y0..y1).flatMap { y -> (x0..x1).map { x -> TileId(zoom, x.mod(n), y) } }
    }
}
