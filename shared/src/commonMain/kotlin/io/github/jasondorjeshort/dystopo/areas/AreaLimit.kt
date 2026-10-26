package io.github.jasondorjeshort.dystopo.areas

import io.github.jasondorjeshort.dystopo.geo.WebMercator
import kotlin.math.floor

/**
 * T140 (ADR 0041 §3): the memory guard of a polygon area. Its tile sets are filled in memory (a hash set per zoom, then
 * the pin cover, the download's work list), so before anything is filled the tiles of each outer ring's own bounding
 * box are counted over the zoom range and checked against a limit derived from the heap; a shape over it is refused
 * with [TOO_LARGE]. Holes are not counted, and each part counts its own box, so a record whose parts lie either side of
 * the antimeridian is not charged for the ocean between them. The pinned budget stays the steer in bytes; this count is
 * never used for it.
 */
object AreaLimit {
    const val TOO_LARGE = "This area is too large at this detail; make it smaller or choose less detail"

    /**
     * Heap bytes a tile costs at the peak of a download (estimated, not measured: a hash-set entry with its boxed key,
     * about 60 bytes, twice over for the manifest and the pin cover, plus the download's work item and the sorted copy
     * the rows are written from).
     */
    const val BYTES_PER_TILE = 256L

    /** The share of the heap one area may fill: a quarter, the rest for the map, its renders and the tile caches. */
    const val HEAP_SHARE = 4L

    /** The tile limit for a heap of [maxHeapBytes] (`Runtime.maxMemory()`). */
    fun maxTiles(maxHeapBytes: Long): Long = maxHeapBytes / HEAP_SHARE / BYTES_PER_TILE

    /** An upper bound of [area]'s tiles at zoom [z] grown by [margin]: each outer ring's box for a polygon, else the area's box. */
    fun upperBound(area: AreaOfInterest, z: Int, margin: Int): Long {
        val boxes = when (val s = area.shape) {
            is AreaShape.Polygon -> PolygonRings.outer(s.rings).map { PolygonRings.bounds(listOf(it), s.bufferM) }
            else -> listOf(area.bounds)
        }
        val world = 1L shl z
        return boxes.sumOf { b ->
            val x0 = floor(WebMercator.lonToX(b.minLon, z)).toLong()
            val x1 = floor(WebMercator.lonToX(b.maxLon, z)).toLong()
            val y0 = floor(WebMercator.latToY(b.maxLat, z)).toLong()
            val y1 = floor(WebMercator.latToY(b.minLat, z)).toLong()
            minOf(x1 - x0 + 1 + 2L * margin, world) * minOf(y1 - y0 + 1 + 2L * margin, world)
        }.coerceAtMost(world * world)
    }

    /** The upper bound over the manifest's DEM zooms and vector zooms ([AreaManifest.tileItems]'s arguments). */
    fun upperBound(area: AreaOfInterest, demZooms: IntRange?, osmZooms: IntRange?, margin: Int = 1): Long =
        listOfNotNull(demZooms, osmZooms).sumOf { zs -> zs.sumOf { upperBound(area, it, margin) } }

    /** [TOO_LARGE] when [area] is a polygon whose upper bound passes [maxTiles]; null when it may be filled (or is no polygon). */
    fun refusal(area: AreaOfInterest, demZooms: IntRange?, osmZooms: IntRange?, maxTiles: Long, margin: Int = 1): String? =
        if (area.shape is AreaShape.Polygon && upperBound(area, demZooms, osmZooms, margin) > maxTiles) TOO_LARGE else null
}
