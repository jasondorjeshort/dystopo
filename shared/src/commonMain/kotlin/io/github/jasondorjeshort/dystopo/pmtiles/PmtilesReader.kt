package io.github.jasondorjeshort.dystopo.pmtiles

import io.github.jasondorjeshort.dystopo.tiff.ByteRangeSource

/**
 * Reads a PMTiles v3 archive through random access, so a file on disk and an archive behind HTTP
 * range requests work the same way. Opening fetches the first 16 KiB once (header plus, in a
 * well-formed archive, the whole root directory). Each tile lookup then costs at most one read per
 * leaf directory (cached) plus one read for the tile itself.
 *
 * Decompressors for [Compression.GZIP] etc. are injected; [Compression.NONE] needs none.
 */
class PmtilesReader private constructor(
    private val source: ByteRangeSource,
    val header: PmtilesHeader,
    val rootDirectory: List<Entry>,
    /** Size of the root directory once decompressed. */
    val rootDirectoryBytes: Int,
    private val head: ByteArray,
    private val decompressors: Map<Compression, Decompress>,
) {
    private val leafCache = HashMap<Long, List<Entry>>()

    /** Leaf directories fetched from the source so far (cache misses). */
    var leafReads: Int = 0
        private set

    /** Metadata JSON text, decompressed. */
    fun metadataJson(): String {
        if (header.metadataLength == 0L) return "{}"
        return decompressInternal(readRange(header.metadataOffset, header.metadataLength.toInt())).decodeToString()
    }

    /** Metadata parsed into maps and lists; see [MiniJson]. */
    fun metadata(): Map<String, Any?> = MiniJson.parseObject(metadataJson())

    /** The tile entry addressing [tileId], following leaf directories; null if the tile is absent. */
    fun findEntry(tileId: Long): Entry? {
        var entries = rootDirectory
        for (depth in 0 until MAX_LEAF_DEPTH) {
            val e = Directory.find(entries, tileId) ?: return null
            if (!e.isLeaf) return e
            entries = leafDirectory(e)
        }
        throw IllegalStateException("leaf directories nested deeper than $MAX_LEAF_DEPTH")
    }

    fun findEntry(z: Int, x: Int, y: Int): Entry? = findEntry(TileIds.fromZxy(z, x, y))

    /** Tile bytes exactly as stored (still compressed if [PmtilesHeader.tileCompression] says so). */
    fun tileStored(z: Int, x: Int, y: Int): ByteArray? {
        val e = findEntry(z, x, y) ?: return null
        return readRange(header.tileDataOffset + e.offset, e.length)
    }

    /** Tile bytes with the archive's tile compression undone, ready for an image or MVT decoder. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? = tileStored(z, x, y)?.let { decompress(header.tileCompression, it) }

    /** Entries of the leaf directory [leaf] points at (cached). */
    fun leafDirectory(leaf: Entry): List<Entry> {
        require(leaf.isLeaf) { "not a leaf entry: $leaf" }
        return leafCache.getOrPut(leaf.offset) {
            leafReads++
            Directory.decode(decompressInternal(readRange(header.leafDirectoriesOffset + leaf.offset, leaf.length)))
        }
    }

    /**
     * Every tile entry in tile-ID order, descending into leaves lazily. Walking a large remote
     * archive this way reads every leaf directory; callers that only want a sample should stop early.
     */
    fun tileEntries(): Sequence<Entry> = sequence {
        for (e in rootDirectory) {
            if (e.isLeaf) yieldAll(leafDirectory(e)) else yield(e)
        }
    }

    fun decompress(compression: Compression, bytes: ByteArray): ByteArray = when (compression) {
        Compression.NONE -> bytes
        else -> (decompressors[compression] ?: throw UnsupportedOperationException("no decompressor for ${compression.label}"))(bytes)
    }

    private fun decompressInternal(bytes: ByteArray) = decompress(header.internalCompression, bytes)

    private fun readRange(offset: Long, length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        if (offset + length <= head.size) return head.copyOfRange(offset.toInt(), offset.toInt() + length)
        val bytes = source.read(offset, length)
        check(bytes.size == length) { "short read at $offset: wanted $length bytes, got ${bytes.size}" }
        return bytes
    }

    companion object {
        /** The spec asks writers to keep header + root directory within this many bytes. */
        const val HEAD_BYTES = 16384
        private const val MAX_LEAF_DEPTH = 4

        fun open(source: ByteRangeSource, decompressors: Map<Compression, Decompress> = emptyMap()): PmtilesReader {
            val head = source.read(0, HEAD_BYTES)
            val header = PmtilesHeader.decode(head)
            val rootEnd = header.rootDirectoryOffset + header.rootDirectoryLength
            val rootCompressed = if (rootEnd <= head.size) {
                head.copyOfRange(header.rootDirectoryOffset.toInt(), rootEnd.toInt())
            } else source.read(header.rootDirectoryOffset, header.rootDirectoryLength.toInt())
            val rootBytes = when (header.internalCompression) {
                Compression.NONE -> rootCompressed
                else -> (decompressors[header.internalCompression]
                    ?: throw UnsupportedOperationException("no decompressor for ${header.internalCompression.label}"))(rootCompressed)
            }
            return PmtilesReader(source, header, Directory.decode(rootBytes), rootBytes.size, head, decompressors)
        }
    }
}
