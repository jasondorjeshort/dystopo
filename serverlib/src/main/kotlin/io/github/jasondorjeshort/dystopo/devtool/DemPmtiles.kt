package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.pmtiles.ByteSink
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesWriter
import io.github.jasondorjeshort.dystopo.pmtiles.TileIds
import io.github.jasondorjeshort.dystopo.pmtiles.TileStaging
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile

/** Tile staging in a temp file, so a region-sized export does not hold every PNG in memory. */
class FileTileStaging(private val file: File) : TileStaging, AutoCloseable {
    private val raf = RandomAccessFile(file, "rw").also { it.setLength(0) }

    override fun append(bytes: ByteArray): Long {
        val at = raf.length()
        raf.seek(at)
        raf.write(bytes)
        return at
    }

    override fun read(position: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        raf.seek(position)
        raf.readFully(out)
        return out
    }

    override fun close() {
        raf.close()
        file.delete()
    }
}

/**
 * Order-independent digest of decoded tile grids: each tile's float bits are hashed (FNV-1a) and
 * mixed with its tile ID, and the per-tile values are summed. Two exports whose tiles decode to the
 * same grids at the same addresses have the same digest, whatever container holds them.
 */
object GridDigest {
    fun tile(tile: TileId, values: FloatArray): Long {
        var h = -0x340d631b7bdddcdbL
        for (v in values) {
            val bits = v.toRawBits()
            for (s in 0 until 32 step 8) {
                h = h xor ((bits ushr s) and 0xFF).toLong()
                h *= 0x100000001b3L
            }
        }
        return mix(h xor (TileIds.fromZxy(tile.z, tile.x, tile.y) * -0x61c8864680b583ebL))
    }

    fun hex(sum: Long): String = "%016x".format(sum)

    private fun mix(x: Long): Long {
        var z = x
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
}

/** `--verify` for the archive: every tile read back from the finished file and decoded. */
@Serializable
data class ArchiveVerify(
    val tilesReadBack: Int,
    /** Tiles rendered and written but absent from the archive. */
    val missingTiles: Int,
    /** Tiles in the archive that the export did not write. */
    val extraTiles: Int,
    /** Tiles whose decoded grid differs from the grid decoded at render time. */
    val gridMismatches: Int,
    /** Per-zoom [GridDigest] of the archive's tiles; equal to the zoom reports' `verify.gridDigest`. */
    val gridDigests: Map<String, String>,
    val digestsMatch: Boolean,
    val readMs: Long,
)

@Serializable
data class PmtilesOutputReport(
    val path: String,
    val bytes: Long,
    /** Sum of the PNG tile sizes, i.e. what the same tiles take as an XYZ directory (before filesystem block overhead). */
    val tileBytes: Long,
    val addressedTiles: Long,
    val tileEntries: Long,
    val tileContents: Long,
    val tileDataBytes: Long,
    val rootDirectoryBytes: Long,
    val leafDirectories: Int,
    val leafDirectoriesBytes: Long,
    val metadataBytes: Long,
    val minZoom: Int,
    val maxZoom: Int,
    val bounds: List<Double>,
    val writeMs: Long,
    val verify: ArchiveVerify? = null,
)

/**
 * Collects Terrarium PNG tiles during `tiles --pmtiles` and writes the archive at the end: tile type
 * PNG, no tile compression (PNG is already compressed), uncompressed directories and metadata (a few
 * KB; keeps the archive readable without a decompressor), the tile-set metadata JSON with registry
 * attribution and provenance, and the export box as header bounds.
 */
class DemPmtilesWriter(private val file: File) : AutoCloseable {
    private val staging = FileTileStaging(File(file.absoluteFile.parentFile.also { it.mkdirs() }, file.name + ".staging"))
    private val writer = PmtilesWriter(TileType.PNG, staging = staging)
    private var pngBytes = 0L

    fun add(tile: TileId, png: ByteArray) {
        writer.addTile(tile.z, tile.x, tile.y, png)
        pngBytes += png.size
    }

    fun finish(metadata: TileSetMetadata, box: BoundingBox, minZoom: Int, maxZoom: Int): PmtilesOutputReport {
        val started = System.nanoTime()
        val json = reportJson.encodeToString(metadata)
        // GeoJSON-style: an antimeridian box is written with west > east, both within -180..180.
        val (w, s, e, n) = TileSets.geoJsonBounds(box)
        val result = BufferedOutputStream(file.outputStream(), 1 shl 16).use { os ->
            writer.finish(
                ByteSink { os.write(it) }, json, w, s, e, n,
                centerZoom = (minZoom + maxZoom + 1) / 2, minZoom = minZoom, maxZoom = maxZoom,
                centerLon = TileSets.centerLon(box), centerLat = box.centerLat,
            )
        }
        val h = result.header
        return PmtilesOutputReport(
            path = file.path,
            bytes = file.length(),
            tileBytes = pngBytes,
            addressedTiles = h.addressedTiles,
            tileEntries = h.tileEntries,
            tileContents = h.tileContents,
            tileDataBytes = h.tileDataLength,
            rootDirectoryBytes = h.rootDirectoryLength,
            leafDirectories = result.leafDirectories,
            leafDirectoriesBytes = result.leafDirectoriesBytes,
            metadataBytes = h.metadataLength,
            minZoom = h.minZoom,
            maxZoom = h.maxZoom,
            bounds = listOf(h.minLon, h.minLat, h.maxLon, h.maxLat),
            writeMs = (System.nanoTime() - started) / 1_000_000,
        )
    }

    override fun close() = staging.close()

    companion object {
        /**
         * Reads every tile of the finished archive, decodes it with [decode], and compares its
         * [GridDigest] with [expected] (recorded when the tile was rendered).
         */
        fun verify(file: File, expected: Map<TileId, Long>, decode: (ByteArray) -> FloatArray): ArchiveVerify {
            val started = System.nanoTime()
            val reader = PmtilesReader.open(FileRangeSource(file))
            val seen = HashSet<TileId>()
            val digests = sortedMapOf<Int, Long>()
            var extra = 0
            var mismatches = 0
            for (e in reader.tileEntries()) for (k in 0 until e.runLength) {
                val t = TileIds.toZxy(e.tileId + k)
                val want = expected[t]
                if (want == null) {
                    extra++
                    continue
                }
                seen += t
                val bytes = reader.tile(t.z, t.x, t.y) ?: continue
                val d = GridDigest.tile(t, decode(bytes))
                if (d != want) mismatches++
                digests[t.z] = (digests[t.z] ?: 0L) + d
            }
            val expectedDigests = sortedMapOf<Int, Long>()
            for ((t, d) in expected) expectedDigests[t.z] = (expectedDigests[t.z] ?: 0L) + d
            return ArchiveVerify(
                tilesReadBack = seen.size,
                missingTiles = expected.keys.count { it !in seen },
                extraTiles = extra,
                gridMismatches = mismatches,
                gridDigests = digests.entries.associate { it.key.toString() to GridDigest.hex(it.value) },
                digestsMatch = digests == expectedDigests,
                readMs = (System.nanoTime() - started) / 1_000_000,
            )
        }
    }
}
