package io.github.jasondorjeshort.dystopo.pmtiles

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.tiff.ByteRangeSource
import kotlin.math.floor

/** What [PmtilesExtract.extract] did, for reports. */
data class ExtractResult(
    val write: WriteResult,
    /** Tiles kept, by zoom. */
    val tilesByZoom: Map<Int, Int>,
    val tilesKept: Int,
    /** Tiles the walk looked at (run lengths expanded) in the directories it read. */
    val tilesScanned: Long,
    val leafDirectoriesRead: Int,
    /** Leaf directories never fetched because no wanted tile can be in their tile-ID range. */
    val leafDirectoriesSkipped: Int,
    /** Tile data reads from the source (one per distinct stored blob that was kept). */
    val tileReads: Int,
    val tileBytesRead: Long,
    /** Bounds written to the new header: the requested box clipped to the input's bounds. */
    val bounds: BoundingBox,
    val minZoom: Int,
    val maxZoom: Int,
)

/**
 * Cuts a sub-archive out of a PMTiles archive: every tile at zooms [minZoom]..[maxZoom] whose
 * extent intersects a bounding box (the same rule as [WebMercator.tilesCovering], so a box drawn on
 * the map gets the tiles a renderer would request for it). Tile bytes are copied as stored, so the
 * tile type and tile compression carry over unchanged.
 *
 * The directory walk skips leaf directories whose tile-ID range holds no wanted tile, so extracting
 * a small area from a large remote archive reads only the leaves that matter (when the wanted set
 * is small enough to enumerate; see [MAX_ENUMERATED_TILES]).
 */
object PmtilesExtract {
    /** Above this many candidate tiles the wanted set is tested per tile instead of enumerated. */
    const val MAX_ENUMERATED_TILES = 1 shl 22

    /** Inclusive tile x/y range of [bbox] at one zoom. */
    private class ZoomRange(val z: Int, val x0: Int, val x1: Int, val y0: Int, val y1: Int) {
        val count: Long get() = (x1 - x0 + 1).toLong() * (y1 - y0 + 1)
        fun contains(t: TileId) = t.x in x0..x1 && t.y in y0..y1
    }

    private fun zoomRange(bbox: BoundingBox, z: Int): ZoomRange {
        val max = (1 shl z) - 1
        return ZoomRange(
            z,
            floor(WebMercator.lonToX(bbox.west, z)).toInt().coerceIn(0, max),
            floor(WebMercator.lonToX(bbox.east, z)).toInt().coerceIn(0, max),
            floor(WebMercator.latToY(bbox.north, z)).toInt().coerceIn(0, max),
            floor(WebMercator.latToY(bbox.south, z)).toInt().coerceIn(0, max),
        )
    }

    /**
     * Reads [source] (a PMTiles archive) and writes the extract to [sink].
     *
     * [minZoom]/[maxZoom] default to the input's range and are clamped to it. [metadataJson] replaces
     * the input's metadata when given (callers usually patch bounds and zooms into it); otherwise it
     * is copied verbatim. Directories and metadata are written with the input's internal compression
     * when [compress] is supplied for it, else uncompressed. Fails if no tile matches.
     *
     * Kept tile contents go to [staging] (the writer's), not memory: pass a temp-file staging for
     * region-sized extracts. Each distinct stored blob is read from [source] once; when the input
     * points several entries at one blob (deduplicated ocean tiles, run lengths), the copy is read
     * back from staging rather than held in a cache.
     */
    fun extract(
        source: ByteRangeSource,
        bbox: BoundingBox,
        sink: ByteSink,
        minZoom: Int? = null,
        maxZoom: Int? = null,
        decompressors: Map<Compression, Decompress> = emptyMap(),
        compress: Compress? = null,
        metadataJson: String? = null,
        staging: TileStaging = MemoryTileStaging(),
    ): ExtractResult {
        val reader = PmtilesReader.open(source, decompressors)
        val h = reader.header
        val zMin = maxOf(minZoom ?: h.minZoom, h.minZoom)
        val zMax = minOf(maxZoom ?: h.maxZoom, h.maxZoom)
        require(zMin <= zMax) { "zoom range ${minZoom ?: h.minZoom}..${maxZoom ?: h.maxZoom} does not overlap the archive's ${h.minZoom}..${h.maxZoom}" }

        val ranges = (zMin..zMax).associateWith { zoomRange(bbox, it) }
        val candidates = ranges.values.sumOf { it.count }
        // Sorted tile IDs of every wanted tile, when that list is affordable.
        val wanted: LongArray? = if (candidates <= MAX_ENUMERATED_TILES) {
            val ids = LongArray(candidates.toInt())
            var i = 0
            for (r in ranges.values) for (y in r.y0..r.y1) for (x in r.x0..r.x1) ids[i++] = TileIds.fromZxy(r.z, x, y)
            ids.sort()
            ids
        } else null

        fun isWanted(id: Long): Boolean {
            if (wanted != null) return wanted.binarySearch(id) >= 0
            val z = TileIds.zoomOf(id)
            val r = ranges[z] ?: return false
            return r.contains(TileIds.toZxy(id))
        }

        /** True if some wanted tile has an ID in [start, end). */
        fun anyWantedIn(start: Long, end: Long): Boolean {
            if (wanted != null) {
                val at = wanted.binarySearch(start).let { if (it >= 0) it else -it - 1 }
                return at < wanted.size && wanted[at] < end
            }
            val zEnd = if (end == Long.MAX_VALUE) TileIds.MAX_ZOOM else TileIds.zoomOf(end - 1)
            return TileIds.zoomOf(start) <= zMax && zEnd >= zMin
        }

        val outInternal = if (compress != null) h.internalCompression else Compression.NONE
        val recording = RecordingStaging(staging)
        val writer = PmtilesWriter(
            tileType = h.tileType,
            tileCompression = h.tileCompression,
            internalCompression = outInternal,
            compress = if (outInternal == Compression.NONE) null else compress,
            staging = recording,
        )
        // Input blob offset -> where the writer staged its bytes. Positions only, never the bytes.
        val staged = HashMap<Long, Long>()
        val byZoom = sortedMapOf<Int, Int>()
        var scanned = 0L
        var skipped = 0
        var tileReads = 0
        var tileBytes = 0L

        fun walk(entries: List<Entry>, end: Long, depth: Int) {
            check(depth <= 4) { "leaf directories nested deeper than 4" }
            for ((i, e) in entries.withIndex()) {
                if (e.isLeaf) {
                    val leafEnd = if (i + 1 < entries.size) entries[i + 1].tileId else end
                    if (anyWantedIn(e.tileId, leafEnd)) walk(reader.leafDirectory(e), leafEnd, depth + 1) else skipped++
                    continue
                }
                scanned += e.runLength
                for (k in 0 until e.runLength) {
                    val id = e.tileId + k
                    if (!isWanted(id)) continue
                    val at = staged[e.offset]
                    if (at != null) {
                        // Same blob again: the writer deduplicates it against its staged copy.
                        writer.addTile(id, staging.read(at, e.length))
                    } else {
                        val b = source.read(h.tileDataOffset + e.offset, e.length)
                        check(b.size == e.length) { "short tile read at ${e.offset}: wanted ${e.length}, got ${b.size}" }
                        tileReads++
                        tileBytes += b.size
                        recording.lastAppend = -1L
                        recording.lastRead = -1L
                        writer.addTile(id, b)
                        // No append means the content matched an earlier blob at another offset (an input that
                        // doesn't deduplicate). The writer confirms a match by reading the candidate back and
                        // stops at the first equal one, so its last staging read is where these bytes live.
                        val stagedAt = if (recording.lastAppend >= 0) recording.lastAppend else recording.lastRead
                        if (stagedAt >= 0) staged[e.offset] = stagedAt
                    }
                    val z = TileIds.zoomOf(id)
                    byZoom[z] = (byZoom[z] ?: 0) + 1
                }
            }
        }
        walk(reader.rootDirectory, Long.MAX_VALUE, 0)
        require(writer.tileCount > 0) { "no tiles of the archive intersect the box at zooms $zMin..$zMax" }

        val west = maxOf(bbox.west, h.minLon)
        val south = maxOf(bbox.south, h.minLat)
        val east = minOf(bbox.east, h.maxLon)
        val north = minOf(bbox.north, h.maxLat)
        // Tiles exist, so the box and the archive's tiles overlap; a degenerate header box falls back to the request.
        val bounds = if (west < east && south < north) BoundingBox(west, south, east, north) else bbox
        val outMin = byZoom.firstKey()
        val outMax = byZoom.lastKey()
        val result = writer.finish(
            sink,
            metadataJson = metadataJson ?: reader.metadataJson(),
            minLon = bounds.west, minLat = bounds.south, maxLon = bounds.east, maxLat = bounds.north,
            centerZoom = h.centerZoom.coerceIn(outMin, outMax),
            centerLon = bounds.centerLon, centerLat = bounds.centerLat,
            minZoom = outMin, maxZoom = outMax,
        )
        return ExtractResult(
            write = result,
            tilesByZoom = byZoom.toMap(),
            tilesKept = writer.tileCount,
            tilesScanned = scanned,
            leafDirectoriesRead = reader.leafReads,
            leafDirectoriesSkipped = skipped,
            tileReads = tileReads,
            tileBytesRead = tileBytes,
            bounds = bounds,
            minZoom = outMin,
            maxZoom = outMax,
        )
    }
}

/** Passes through to [inner], remembering where the last [append] landed and the last [read] came from. */
private class RecordingStaging(private val inner: TileStaging) : TileStaging {
    var lastAppend = -1L
    var lastRead = -1L

    override fun append(bytes: ByteArray): Long = inner.append(bytes).also { lastAppend = it }

    override fun read(position: Long, length: Int): ByteArray = inner.read(position, length).also { lastRead = position }
}
