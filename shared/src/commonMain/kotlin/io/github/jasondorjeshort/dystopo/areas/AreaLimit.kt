package io.github.jasondorjeshort.dystopo.areas

import io.github.jasondorjeshort.dystopo.geo.WebMercator
import kotlin.math.floor

/**
 * T140, T152 (ADR 0041 §3 and its T152 note): what an area's tiles cost the phone's heap.
 *
 * T140 refused a polygon whose tiles passed a heap-derived limit, because its tile sets were hash sets of every tile (the
 * manifest's, the pin cover's) and the download expanded every tile into a work list. Since T152 the sets are rows
 * ([TileSet]), the download walks the manifest's rows ([AreaManifest.walk]) and the pins are rows too, so no structure
 * that lives for the whole area grows with its tiles: the heap is no size limit and the heap refuses no area. The
 * pinned budget (`pinnedCacheMb`) is the one steer of the phone's own, in bytes, and it is visible (ADR 0023). T369: one
 * refusal lives here, and it is not the phone's: with no server set, a download takes its vector tiles from the public
 * vector source, whose terms allow at most [DIRECT_MAX_TILES] tiles and [DIRECT_MAX_BYTES] a download ([overDirectCap],
 * [directBytesReached]); a server set has no such cap.
 *
 * One per-tile structure remains: the online tile cache's index (`TileCache`, one entry per cached file, held for every
 * tile on the phone, an area's or not, from the cache's start-up scan on). [BYTES_PER_TILE] is its cost and [maxTiles]
 * the tiles a quarter of the heap holds of it; a plan past that says so ([pastHeap], `pastHeapLimit` in the plan) and the
 * download proceeds all the same (the owner, 2026-10-26: "I want to be able to download 100 GB if I want to").
 */
object AreaLimit {
    /**
     * Heap bytes one cached tile costs the tile cache's index on Android (estimated from ART's object layout, not
     * measured): the key string (about 45 ASCII characters, compressed: 64 B), the entry (size, last use, pinned flag:
     * 32 B), the access-ordered map's node (32 B) and its table slot (8 B), with slack for longer keys. T152 dropped the
     * `File` each entry held (its absolute path string, about 130 B more).
     */
    const val BYTES_PER_TILE = 160L

    /** The share of the heap the cache index may fill: a quarter, the rest for the map, its renders and the tile caches. */
    const val HEAP_SHARE = 4L

    /** The cached tiles a heap of [maxHeapBytes] (`Runtime.maxMemory()`) holds the index of comfortably. */
    fun maxTiles(maxHeapBytes: Long): Long = maxHeapBytes / HEAP_SHARE / BYTES_PER_TILE

    /** T152: true when [tiles] (an area's) pass [maxTiles]: reported with the plan, never a refusal. */
    fun pastHeap(tiles: Long, maxTiles: Long): Boolean = tiles > maxTiles

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

    // T369
    /**
     * T369: the public vector source's figure for one offline download (its terms, read 2026-11-24, in the registry's
     * `openfreemap` entry): at most 3,000 tiles. A rule of terms, not a setting (ADR 0044). Every vector tile of the plan
     * counts, one a peer or the phone already holds included (simpler, and never over).
     */
    const val DIRECT_MAX_TILES = 3_000L

    /**
     * T369: the same terms' second figure: at most 50 MB a download, taken as 50,000,000 bytes (the smaller reading) and
     * counted as fetched, gunzipped as the phone stores them (a little more than went over the wire: never over).
     */
    const val DIRECT_MAX_BYTES = 50_000_000L

    /** T369: true when a plan of [vectorTiles] passes [DIRECT_MAX_TILES] (3,000 is allowed, 3,001 is not). */
    fun overDirectCap(vectorTiles: Long): Boolean = vectorTiles > DIRECT_MAX_TILES

    /**
     * T369: true once a download has fetched [vectorBytes] of vector tiles that reach [DIRECT_MAX_BYTES] less room for the
     * tiles still on their way ([inFlight], the caller's fetching threads, each tile taken as [largestTile], the largest
     * fetched so far): it stops there, so what is on its way lands at or under the 50 MB. Measured against a stub of 200 kB
     * tiles: with no room 51,194,368 bytes (256 tiles); with room for the 4 permits 50,394,456 (252: the area download's 6
     * threads were all inside a fetch, 4 holding a permit and 2 waiting for one).
     */
    fun directBytesReached(vectorBytes: Long, largestTile: Long = 0L, inFlight: Int = 0): Boolean = vectorBytes + inFlight * largestTile >= DIRECT_MAX_BYTES
    // end T369
}
