package io.github.jasondorjeshort.dystopo.pmtiles

/**
 * Holds tile contents between [PmtilesWriter.addTile] and [PmtilesWriter.finish]. The in-memory
 * default suits tests and area-sized packs; a platform can supply a temp-file implementation for
 * region-sized builds.
 */
interface TileStaging {
    /** Appends [bytes] and returns their position, to be passed to [read] later. */
    fun append(bytes: ByteArray): Long
    fun read(position: Long, length: Int): ByteArray
}

class MemoryTileStaging : TileStaging {
    private val chunks = ArrayList<ByteArray>()
    private val starts = ArrayList<Long>()
    private var total = 0L

    override fun append(bytes: ByteArray): Long {
        val at = total
        chunks += bytes
        starts += at
        total += bytes.size
        return at
    }

    override fun read(position: Long, length: Int): ByteArray {
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= position) lo = mid else hi = mid - 1
        }
        val chunk = chunks[lo]
        val from = (position - starts[lo]).toInt()
        require(from == 0 && length == chunk.size) { "staging read does not match an appended blob" }
        return chunk
    }
}

/** What [PmtilesWriter.finish] produced, for reports. */
data class WriteResult(
    val header: PmtilesHeader,
    val totalBytes: Long,
    val rootDirectoryBytes: Int,
    val rootDirectoryCompressedBytes: Int,
    val leafDirectories: Int,
    val leafDirectoriesBytes: Long,
    /** Entries per leaf when leaves were needed; 0 for a root-only archive. */
    val leafSize: Int,
)

/**
 * Builds a PMTiles v3 archive: tiles may be added in any order; [finish] sorts them by tile ID,
 * deduplicates identical contents, run-length-merges consecutive identical tiles, lays tile data
 * out clustered in tile-ID order, and splits the directory into leaves when the root would not fit
 * the spec's 16 KiB budget together with the header.
 *
 * Tile bytes are stored as given; [tileCompression] declares what compression they already carry.
 * [compress] is applied to directories and metadata when [internalCompression] is not NONE.
 */
class PmtilesWriter(
    private val tileType: TileType,
    private val tileCompression: Compression = Compression.NONE,
    private val internalCompression: Compression = Compression.NONE,
    private val compress: Compress? = null,
    private val staging: TileStaging = MemoryTileStaging(),
    /** Root directory budget: header + compressed root must not exceed this. */
    private val maxHeadBytes: Int = PmtilesReader.HEAD_BYTES,
    /** Starting number of entries per leaf; doubled until the root fits. */
    private val initialLeafSize: Int = 4096,
) {
    init {
        require(internalCompression == Compression.NONE || compress != null) {
            "internalCompression ${internalCompression.label} needs a compressor"
        }
    }

    private class Blob(val position: Long, val length: Int)
    private class Placed(val tileId: Long, val blob: Int)

    private val blobs = ArrayList<Blob>()
    private val blobsByHash = HashMap<Long, MutableList<Int>>()
    private val tiles = ArrayList<Placed>()
    private val seen = HashSet<Long>()

    val tileCount: Int get() = tiles.size
    val distinctContents: Int get() = blobs.size

    fun addTile(z: Int, x: Int, y: Int, bytes: ByteArray) = addTile(TileIds.fromZxy(z, x, y), bytes)

    fun addTile(tileId: Long, bytes: ByteArray) {
        require(seen.add(tileId)) { "tile ${TileIds.toZxy(tileId)} added twice" }
        require(bytes.isNotEmpty()) { "empty tile ${TileIds.toZxy(tileId)}; leave absent tiles out" }
        tiles += Placed(tileId, blobIndex(bytes))
    }

    private fun blobIndex(bytes: ByteArray): Int {
        val h = hash(bytes)
        val candidates = blobsByHash.getOrPut(h) { ArrayList(1) }
        for (i in candidates) {
            val b = blobs[i]
            if (b.length == bytes.size && staging.read(b.position, b.length).contentEquals(bytes)) return i
        }
        val index = blobs.size
        blobs += Blob(staging.append(bytes), bytes.size)
        candidates += index
        return index
    }

    /**
     * Writes the archive to [sink]. Zoom range defaults to the tiles' range; bounds default to the
     * world and the center to its middle at the minimum zoom. Tile data is streamed out of staging
     * in tile-ID order.
     */
    fun finish(
        sink: ByteSink,
        metadataJson: String = "{}",
        minLon: Double = -180.0,
        minLat: Double = -85.051129,
        maxLon: Double = 180.0,
        maxLat: Double = 85.051129,
        centerZoom: Int? = null,
        centerLon: Double = (minLon + maxLon) / 2,
        centerLat: Double = (minLat + maxLat) / 2,
        minZoom: Int? = null,
        maxZoom: Int? = null,
    ): WriteResult {
        require(tiles.isNotEmpty()) { "no tiles added" }
        tiles.sortBy { it.tileId }

        // Cluster: blobs appear in the tile data section in order of first use along the tile-ID line.
        val blobOffsets = LongArray(blobs.size) { -1L }
        val blobOrder = ArrayList<Int>(blobs.size)
        var tileDataLength = 0L
        for (t in tiles) {
            if (blobOffsets[t.blob] < 0) {
                blobOffsets[t.blob] = tileDataLength
                blobOrder += t.blob
                tileDataLength += blobs[t.blob].length
            }
        }

        // Run-length merge consecutive tiles that share contents.
        val entries = ArrayList<Entry>(tiles.size)
        for (t in tiles) {
            val b = blobs[t.blob]
            val last = entries.lastOrNull()
            if (last != null && last.offset == blobOffsets[t.blob] && last.length == b.length &&
                last.tileId + last.runLength == t.tileId
            ) {
                entries[entries.size - 1] = last.copy(runLength = last.runLength + 1)
            } else entries += Entry(t.tileId, blobOffsets[t.blob], b.length, 1)
        }

        val dirs = buildDirectories(entries)
        val metadata = compressInternal(metadataJson.encodeToByteArray())

        val zMin = minZoom ?: TileIds.zoomOf(tiles.first().tileId)
        val zMax = maxZoom ?: TileIds.zoomOf(tiles.last().tileId)
        val rootOffset = PmtilesHeader.SIZE.toLong()
        val metadataOffset = rootOffset + dirs.root.size
        val leavesOffset = metadataOffset + metadata.size
        val leavesLength = dirs.leaves.sumOf { it.size.toLong() }
        val tileDataOffset = leavesOffset + leavesLength
        val header = PmtilesHeader(
            rootDirectoryOffset = rootOffset,
            rootDirectoryLength = dirs.root.size.toLong(),
            metadataOffset = metadataOffset,
            metadataLength = metadata.size.toLong(),
            leafDirectoriesOffset = leavesOffset,
            leafDirectoriesLength = leavesLength,
            tileDataOffset = tileDataOffset,
            tileDataLength = tileDataLength,
            addressedTiles = tiles.size.toLong(),
            tileEntries = entries.size.toLong(),
            tileContents = blobs.size.toLong(),
            clustered = true,
            internalCompression = internalCompression,
            tileCompression = tileCompression,
            tileType = tileType,
            minZoom = zMin,
            maxZoom = zMax,
            minLon = minLon,
            minLat = minLat,
            maxLon = maxLon,
            maxLat = maxLat,
            centerZoom = centerZoom ?: zMin,
            centerLon = centerLon,
            centerLat = centerLat,
        )

        sink.write(header.encode())
        sink.write(dirs.root)
        sink.write(metadata)
        for (leaf in dirs.leaves) sink.write(leaf)
        for (i in blobOrder) {
            val b = blobs[i]
            sink.write(staging.read(b.position, b.length))
        }
        return WriteResult(
            header = header,
            totalBytes = tileDataOffset + tileDataLength,
            rootDirectoryBytes = dirs.rootUncompressedBytes,
            rootDirectoryCompressedBytes = dirs.root.size,
            leafDirectories = dirs.leaves.size,
            leafDirectoriesBytes = leavesLength,
            leafSize = dirs.leafSize,
        )
    }

    private class Directories(val root: ByteArray, val rootUncompressedBytes: Int, val leaves: List<ByteArray>, val leafSize: Int)

    private fun buildDirectories(entries: List<Entry>): Directories {
        val budget = maxHeadBytes - PmtilesHeader.SIZE
        val flat = Directory.encode(entries)
        val flatCompressed = compressInternal(flat)
        if (flatCompressed.size <= budget) return Directories(flatCompressed, flat.size, emptyList(), 0)

        var leafSize = initialLeafSize
        while (true) {
            val leaves = ArrayList<ByteArray>()
            val rootEntries = ArrayList<Entry>()
            var leafOffset = 0L
            var i = 0
            while (i < entries.size) {
                val chunk = entries.subList(i, minOf(entries.size, i + leafSize))
                val leaf = compressInternal(Directory.encode(chunk))
                rootEntries += Entry(chunk.first().tileId, leafOffset, leaf.size, 0)
                leaves += leaf
                leafOffset += leaf.size
                i += leafSize
            }
            val root = Directory.encode(rootEntries)
            val rootCompressed = compressInternal(root)
            if (rootCompressed.size <= budget) return Directories(rootCompressed, root.size, leaves, leafSize)
            leafSize *= 2
        }
    }

    private fun compressInternal(bytes: ByteArray): ByteArray =
        if (internalCompression == Compression.NONE || compress == null) bytes else compress.invoke(bytes)

    companion object {
        /** FNV-1a over the bytes; collisions are resolved by comparing contents. */
        private fun hash(bytes: ByteArray): Long {
            var h = -0x340d631b7bdddcdbL
            for (b in bytes) {
                h = h xor (b.toLong() and 0xFF)
                h *= 0x100000001b3L
            }
            return h
        }
    }
}
