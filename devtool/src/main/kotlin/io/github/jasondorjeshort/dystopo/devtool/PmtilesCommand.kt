package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.pmtiles.Compress
import io.github.jasondorjeshort.dystopo.pmtiles.Compression
import io.github.jasondorjeshort.dystopo.pmtiles.Decompress
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.pmtiles.TileIds
import io.github.jasondorjeshort.dystopo.tiff.ByteRangeSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.floor

/** JVM gzip for PMTiles directories, metadata and MVT tiles. */
object Gzip {
    val compress: Compress = { input ->
        ByteArrayOutputStream(input.size / 2 + 64).also { out -> GZIPOutputStream(out).use { it.write(input) } }.toByteArray()
    }
    val decompress: Decompress = { input -> GZIPInputStream(input.inputStream()).use { it.readAllBytes() } }
    val decompressors: Map<Compression, Decompress> = mapOf(Compression.GZIP to decompress)
}

/** Random access into a local file. */
class FileRangeSource(private val file: File) : ByteRangeSource {
    override fun read(offset: Long, length: Int): ByteArray = RandomAccessFile(file, "r").use { raf ->
        val n = minOf(length.toLong(), maxOf(0L, raf.length() - offset)).toInt()
        val out = ByteArray(n)
        raf.seek(offset)
        raf.readFully(out)
        out
    }
}

/** Counts reads and bytes going through to [inner]. */
class CountingRangeSource(private val inner: ByteRangeSource) : ByteRangeSource {
    var reads = 0L
    var bytes = 0L

    override fun read(offset: Long, length: Int): ByteArray = inner.read(offset, length).also { reads++; bytes += it.size }
}

@Serializable
data class HeaderReport(
    val version: Int,
    val rootDirectoryOffset: Long,
    val rootDirectoryLength: Long,
    val metadataOffset: Long,
    val metadataLength: Long,
    val leafDirectoriesOffset: Long,
    val leafDirectoriesLength: Long,
    val tileDataOffset: Long,
    val tileDataLength: Long,
    val addressedTiles: Long,
    val tileEntries: Long,
    val tileContents: Long,
    val clustered: Boolean,
    val internalCompression: String,
    val tileCompression: String,
    val tileType: String,
    val minZoom: Int,
    val maxZoom: Int,
    val bounds: List<Double>,
    val centerZoom: Int,
    val center: List<Double>,
)

@Serializable
data class DirectoryReport(
    val rootEntries: Int,
    val rootTileEntries: Int,
    val rootLeafEntries: Int,
    val rootBytes: Int,
    val rootCompressedBytes: Long,
    val leafDirectoriesBytes: Long,
    /** "complete" when every leaf was read, "root-only" when leaves were skipped (default for URLs). */
    val walk: String,
    val leafDirectoriesRead: Int,
    val tileEntries: Long,
    val addressedTiles: Long,
    val tilesByZoom: Map<String, Long>,
    val entriesByZoom: Map<String, Long>,
)

@Serializable
data class TileReport(
    val z: Int,
    val x: Int,
    val y: Int,
    val tileId: Long,
    val found: Boolean,
    val offset: Long? = null,
    val storedBytes: Int? = null,
    val bytes: Int? = null,
    val runLength: Int? = null,
    /** Format guessed from the first bytes of the decompressed tile. */
    val sniff: String? = null,
    val writtenTo: String? = null,
)

@Serializable
data class PmtilesInfoReport(
    val source: String,
    val sourceBytes: Long? = null,
    val header: HeaderReport,
    val metadata: JsonElement,
    val directory: DirectoryReport,
    val tiles: List<TileReport>,
    /** Reads issued to the file or over the network (HTTP: cache misses only); every read is one range request. */
    val rangeRequests: Long,
    val rangeRequestBytes: Long,
    /** All reads the reader asked for, including ones served from the on-disk range cache. */
    val rangeReads: Long,
    val timingsMs: Map<String, Long>,
)

class PmtilesCommand : CliktCommand(name = "pmtiles") {
    init {
        subcommands(PmtilesInfoCommand())
    }

    override fun help(context: Context) = "Inspect PMTiles archives (local file or HTTP range requests)."

    override fun run() = Unit
}

class PmtilesInfoCommand : CliktCommand(name = "info") {
    override fun help(context: Context) =
        "Print a PMTiles archive's header, metadata, directory statistics and a few sample tiles as JSON."

    private val file by option("--file", help = "Local .pmtiles file").file(mustExist = true, canBeDir = false)
    private val url by option("--url", help = "http(s) URL of an archive; read with range requests, cached under cache/pmtiles")
    private val tiles by option("--tile", help = "Tile z/x/y to fetch; repeatable. Default: the center tile at min, center and max zoom")
        .multiple()
    private val walkLeaves by option("--walk-leaves", help = "Read every leaf directory for exact per-zoom counts (default for --file; one request per leaf for --url)")
        .flag()
    private val out by option("--out", help = "Directory to write the sampled tiles into (decompressed, named z-x-y.<ext>)").file(canBeDir = true)

    override fun run() {
        val timings = Timings()
        val (label, raw, sourceBytes) = when {
            file != null && url != null -> throw UsageError("use --file or --url, not both")
            file != null -> Triple(file!!.path, FileRangeSource(file!!), file!!.length())
            url != null -> Triple(url!!, cachedSource(url!!), null)
            else -> throw UsageError("one of --file or --url is required")
        }
        val source = CountingRangeSource(raw)
        val reader = timings.time("open") { PmtilesReader.open(source, Gzip.decompressors) }
        val h = reader.header

        val metadataText = timings.time("metadata") { reader.metadataJson() }
        val metadata = runCatching { Json.parseToJsonElement(metadataText) }.getOrElse { JsonPrimitive(metadataText) }

        val walk = walkLeaves || file != null
        val tilesByZoom = sortedMapOf<Int, Long>()
        val entriesByZoom = sortedMapOf<Int, Long>()
        var tileEntries = 0L
        var addressed = 0L
        timings.time("directoryWalk") {
            val entries = if (walk) reader.tileEntries() else reader.rootDirectory.asSequence().filter { !it.isLeaf }
            for (e in entries) {
                val z = TileIds.zoomOf(e.tileId)
                tilesByZoom.merge(z, e.runLength.toLong(), Long::plus)
                entriesByZoom.merge(z, 1L, Long::plus)
                tileEntries++
                addressed += e.runLength
            }
        }
        val directory = DirectoryReport(
            rootEntries = reader.rootDirectory.size,
            rootTileEntries = reader.rootDirectory.count { !it.isLeaf },
            rootLeafEntries = reader.rootDirectory.count { it.isLeaf },
            rootBytes = reader.rootDirectoryBytes,
            rootCompressedBytes = h.rootDirectoryLength,
            leafDirectoriesBytes = h.leafDirectoriesLength,
            walk = if (walk || reader.rootDirectory.none { it.isLeaf }) "complete" else "root-only",
            leafDirectoriesRead = reader.leafReads,
            tileEntries = tileEntries,
            addressedTiles = addressed,
            tilesByZoom = tilesByZoom.mapKeys { it.key.toString() },
            entriesByZoom = entriesByZoom.mapKeys { it.key.toString() },
        )

        val wanted = if (tiles.isEmpty()) defaultTiles(h.minZoom, h.centerZoom, h.maxZoom, h.centerLon, h.centerLat) else tiles.map(::parseTile)
        out?.mkdirs()
        val tileReports = timings.time("tiles") {
            wanted.map { t ->
                val id = TileIds.fromZxy(t.z, t.x, t.y)
                val entry = reader.findEntry(id) ?: return@map TileReport(t.z, t.x, t.y, id, found = false)
                val stored = reader.tileStored(t.z, t.x, t.y)!!
                val bytes = reader.decompress(h.tileCompression, stored)
                val written = out?.let { dir ->
                    File(dir, "${t.z}-${t.x}-${t.y}.${h.tileType.extension}").also { it.writeBytes(bytes) }.path
                }
                TileReport(t.z, t.x, t.y, id, true, entry.offset, stored.size, bytes.size, entry.runLength, sniff(bytes), written)
            }
        }

        val report = PmtilesInfoReport(
            source = label,
            sourceBytes = sourceBytes,
            header = HeaderReport(
                version = 3,
                rootDirectoryOffset = h.rootDirectoryOffset, rootDirectoryLength = h.rootDirectoryLength,
                metadataOffset = h.metadataOffset, metadataLength = h.metadataLength,
                leafDirectoriesOffset = h.leafDirectoriesOffset, leafDirectoriesLength = h.leafDirectoriesLength,
                tileDataOffset = h.tileDataOffset, tileDataLength = h.tileDataLength,
                addressedTiles = h.addressedTiles, tileEntries = h.tileEntries, tileContents = h.tileContents,
                clustered = h.clustered,
                internalCompression = h.internalCompression.label, tileCompression = h.tileCompression.label,
                tileType = h.tileType.label,
                minZoom = h.minZoom, maxZoom = h.maxZoom,
                bounds = listOf(h.minLon, h.minLat, h.maxLon, h.maxLat),
                centerZoom = h.centerZoom, center = listOf(h.centerLon, h.centerLat),
            ),
            metadata = metadata,
            directory = directory,
            tiles = tileReports,
            rangeRequests = (raw as? CachedHttpRangeSource)?.fetches ?: source.reads,
            rangeRequestBytes = source.bytes,
            rangeReads = source.reads,
            timingsMs = timings.ms,
        )
        echo(reportJson.encodeToString(report))
    }

    private fun cachedSource(url: String): CachedHttpRangeSource {
        val uri = runCatching { URI(url) }.getOrElse { throw UsageError("bad --url: $url") }
        if (uri.scheme != "http" && uri.scheme != "https") throw UsageError("--url must be http(s)")
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        val name = (uri.path.substringAfterLast('/').ifBlank { "archive" }).replace(Regex("[^A-Za-z0-9._-]"), "_")
        return CachedHttpRangeSource(url, Caches.dir("pmtiles/${uri.host}-$name-$digest"))
    }

    private fun parseTile(text: String): TileId {
        val p = text.split('/').map { it.trim().toIntOrNull() }
        if (p.size != 3 || p.any { it == null }) throw UsageError("--tile must be z/x/y, got '$text'")
        val (z, x, y) = p.map { it!! }
        if (z !in 0..TileIds.MAX_ZOOM || x !in 0 until (1 shl z) || y !in 0 until (1 shl z)) throw UsageError("tile $text out of range")
        return TileId(z, x, y)
    }

    /** The tile under the header's center point at the min, center and max zooms (deduplicated). */
    private fun defaultTiles(minZoom: Int, centerZoom: Int, maxZoom: Int, lon: Double, lat: Double): List<TileId> =
        listOf(minZoom, centerZoom, maxZoom).distinct().sorted().filter { it in 0..TileIds.MAX_ZOOM }.map { z ->
            val max = (1 shl z) - 1
            TileId(z, floor(WebMercator.lonToX(lon, z)).toInt().coerceIn(0, max), floor(WebMercator.latToY(lat, z)).toInt().coerceIn(0, max))
        }

    private fun sniff(b: ByteArray): String {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "png"
            at(0) == 0xFF && at(1) == 0xD8 -> "jpeg"
            at(0) == 0x1F && at(1) == 0x8B -> "gzip"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 && at(8) == 0x57 && at(9) == 0x45 -> "webp"
            at(0) == 0x1A -> "protobuf" // MVT tiles start with a layer field: tag 3, length-delimited
            else -> "unknown"
        }
    }
}
