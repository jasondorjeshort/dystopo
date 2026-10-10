package io.github.jasondorjeshort.dystopo.pmtiles

import io.github.jasondorjeshort.dystopo.geo.TileId

/**
 * PMTiles tile IDs: all tiles of every zoom on one 64-bit number line. Zoom levels are laid out
 * one after another (zoom z starts at (4^z - 1) / 3), and within a zoom tiles follow a Hilbert
 * curve so that tiles near each other on the map have IDs near each other. The curve starts at
 * the north-west tile and visits the south-west tile next, so at zoom 1 the order is
 * (0,0), (0,1), (1,1), (1,0).
 */
object TileIds {
    /** Highest zoom whose IDs fit in a positive 64-bit integer. */
    const val MAX_ZOOM = 30

    /** First tile ID of zoom [z]: the number of tiles in all coarser zooms. */
    fun zoomStart(z: Int): Long {
        require(z in 0..MAX_ZOOM) { "zoom $z out of range" }
        return ((1L shl (2 * z)) - 1) / 3
    }

    fun fromZxy(z: Int, x: Int, y: Int): Long {
        val n = 1L shl z
        require(z in 0..MAX_ZOOM && x in 0 until n && y in 0 until n) { "tile $z/$x/$y out of range" }
        var tx = x.toLong()
        var ty = y.toLong()
        var d = 0L
        var s = n shr 1
        while (s > 0) {
            val rx = if (tx and s != 0L) 1L else 0L
            val ry = if (ty and s != 0L) 1L else 0L
            d += s * s * ((3 * rx) xor ry)
            // Rotate/flip the quadrant so the lower bits continue the curve.
            if (ry == 0L) {
                if (rx == 1L) {
                    tx = s - 1 - (tx and (s - 1))
                    ty = s - 1 - (ty and (s - 1))
                }
                val t = tx; tx = ty; ty = t
            }
            s = s shr 1
        }
        return zoomStart(z) + d
    }

    fun fromZxy(tile: TileId): Long = fromZxy(tile.z, tile.x, tile.y)

    fun toZxy(id: Long): TileId {
        require(id >= 0) { "tile id $id out of range" }
        var z = 0
        while (z < MAX_ZOOM && zoomStart(z + 1) <= id) z++
        require(id < zoomStart(z) + (1L shl (2 * z))) { "tile id $id out of range" }
        var t = id - zoomStart(z)
        var x = 0L
        var y = 0L
        var s = 1L
        val n = 1L shl z
        while (s < n) {
            val rx = 1L and (t shr 1)
            val ry = 1L and (t xor rx)
            if (ry == 0L) {
                if (rx == 1L) {
                    x = s - 1 - x
                    y = s - 1 - y
                }
                val tmp = x; x = y; y = tmp
            }
            x += s * rx
            y += s * ry
            t = t shr 2
            s = s shl 1
        }
        return TileId(z, x.toInt(), y.toInt())
    }

    /** Zoom level of a tile ID without decoding its position. */
    fun zoomOf(id: Long): Int {
        var z = 0
        while (z < MAX_ZOOM && zoomStart(z + 1) <= id) z++
        return z
    }
}
