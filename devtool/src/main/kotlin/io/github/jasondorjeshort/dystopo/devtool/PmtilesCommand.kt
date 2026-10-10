package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.server.*

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.pmtiles.ByteSink
import io.github.jasondorjeshort.dystopo.pmtiles.Compress
import io.github.jasondorjeshort.dystopo.pmtiles.Compression
import io.github.jasondorjeshort.dystopo.pmtiles.Decompress
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesExtract
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.pmtiles.TileIds
import io.github.jasondorjeshort.dystopo.tiff.ByteRangeSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.floor

/**
 * Runs a pmtiles subcommand's work on archive [label]; any failure reading it (a corrupt or truncated
 * archive: bad header, garbage directories, gzip errors, short reads, or an unreachable URL) becomes
 * one error line on stderr and exit status 1 instead of a stack trace. Clikt's own errors pass through.
 */
internal fun archiveErrorsAsOneLine(command: String, label: String, block: () -> Unit) {
    try {
        block()
    } catch (e: CliktError) {
        throw e
    } catch (e: Exception) {
        val detail = e.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "no detail"
        throw CliktError("pmtiles $command failed on $label: ${e::class.simpleName}: $detail", statusCode = 1)
    }
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
        subcommands(PmtilesInfoCommand(), PmtilesExtractCommand())
    }

    override fun help(context: Context) = "Inspect PMTiles archives (local file or HTTP range requests) and cut sub-archives out of them."

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
        archiveErrorsAsOneLine("info", label) { info(timings, label, raw, sourceBytes) }
    }

    private fun info(timings: Timings, label: String, raw: ByteRangeSource, sourceBytes: Long?) {
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

    internal fun cachedSource(url: String): CachedHttpRangeSource {
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

@Serializable
data class ExtractCheck(
    /** Tiles in the written archive, by zoom, from walking its directory. */
    val tilesByZoom: Map<String, Long>,
    /** Tiles in the written archive outside the requested box (must be 0). */
    val tilesOutsideBox: Long,
    /** Tiles of the box the input has but the output lacks (must be 0); null when the box holds too many tiles to enumerate. */
    val tilesMissing: Long?,
)

@Serializable
data class PmtilesExtractReport(
    val source: String,
    val sourceBytes: Long? = null,
    val output: String,
    val outputBytes: Long,
    val bbox: List<Double>,
    val requestedZooms: List<Int?>,
    val minZoom: Int,
    val maxZoom: Int,
    /** Header bounds of the output: the box clipped to the input's bounds. */
    val bounds: List<Double>,
    val tileType: String,
    val tileCompression: String,
    val internalCompression: String,
    val tilesKept: Int,
    val tilesByZoom: Map<String, Int>,
    val tileContents: Long,
    val tilesScanned: Long,
    val leafDirectoriesRead: Int,
    val leafDirectoriesSkipped: Int,
    val outputLeafDirectories: Int,
    val tileReads: Int,
    val tileBytesRead: Long,
    val rangeRequests: Long,
    val rangeRequestBytes: Long,
    val check: ExtractCheck,
    val timingsMs: Map<String, Long>,
)

class PmtilesExtractCommand : CliktCommand(name = "extract") {
    override fun help(context: Context) =
        "Cut a sub-archive: every tile at the chosen zooms whose extent intersects --bbox, copied byte for byte, " +
            "with header bounds clipped to the box and metadata bounds/zooms updated. Prints a JSON report."

    private val file by option("--file", help = "Local .pmtiles file").file(mustExist = true, canBeDir = false)
    private val url by option("--url", help = "http(s) URL of an archive; read with range requests, cached under cache/pmtiles")
    private val area by option("--area", help = "Named area (see 'devtool areas') instead of --bbox")
    private val bbox by option("--bbox", help = "west,south,east,north in degrees")
    private val minZoom by option("--min-zoom", help = "Lowest zoom to keep (default: the archive's)").int()
    private val maxZoom by option("--max-zoom", help = "Highest zoom to keep (default: the archive's)").int()
    private val out by option("--out", help = "Output .pmtiles file").file(canBeDir = false).required()

    override fun run() {
        val timings = Timings()
        val (_, box) = Areas.resolve(area, bbox)
        val (label, raw, sourceBytes) = when {
            file != null && url != null -> throw UsageError("use --file or --url, not both")
            file != null -> Triple(file!!.path, FileRangeSource(file!!), file!!.length())
            url != null -> Triple(url!!, PmtilesInfoCommand().cachedSource(url!!), null)
            else -> throw UsageError("one of --file or --url is required")
        }
        if (file != null && file!!.canonicalFile == out.canonicalFile) throw UsageError("--out must differ from --file")
        archiveErrorsAsOneLine("extract", label) { extract(timings, box, label, raw, sourceBytes) }
    }

    private fun extract(timings: Timings, box: BoundingBox, label: String, raw: ByteRangeSource, sourceBytes: Long?) {
        val source = CountingRangeSource(raw)
        val input = timings.time("open") { PmtilesReader.open(source, Gzip.decompressors) }
        val zMin = maxOf(minZoom ?: input.header.minZoom, input.header.minZoom)
        val zMax = minOf(maxZoom ?: input.header.maxZoom, input.header.maxZoom)
        if (zMin > zMax) throw CliktError("zooms ${minZoom ?: "-"}..${maxZoom ?: "-"} do not overlap the archive's ${input.header.minZoom}..${input.header.maxZoom}")

        val metadata = patchMetadata(input.metadataJson(), box, zMin, zMax)
        out.absoluteFile.parentFile.mkdirs()
        val result = timings.time("extract") {
            // Kept tiles are staged in a temp file next to the output, not in memory.
            val staging = FileTileStaging(File(out.absoluteFile.parentFile, out.name + ".staging"))
            try {
                BufferedOutputStream(out.outputStream(), 1 shl 16).use { os ->
                    PmtilesExtract.extract(
                        source, box, ByteSink { os.write(it) }, zMin, zMax,
                        decompressors = Gzip.decompressors, compress = Gzip.compress, metadataJson = metadata,
                        staging = staging,
                    )
                }
            } catch (e: Exception) {
                out.delete()
                throw e
            } finally {
                staging.close()
            }
        }
        val check = timings.time("check") { check(input, box, zMin, zMax) }
        val h = result.write.header
        val report = PmtilesExtractReport(
            source = label,
            sourceBytes = sourceBytes,
            output = out.path,
            outputBytes = out.length(),
            bbox = listOf(box.west, box.south, box.east, box.north),
            requestedZooms = listOf(minZoom, maxZoom),
            minZoom = result.minZoom,
            maxZoom = result.maxZoom,
            bounds = listOf(h.minLon, h.minLat, h.maxLon, h.maxLat),
            tileType = h.tileType.label,
            tileCompression = h.tileCompression.label,
            internalCompression = h.internalCompression.label,
            tilesKept = result.tilesKept,
            tilesByZoom = result.tilesByZoom.mapKeys { it.key.toString() },
            tileContents = h.tileContents,
            tilesScanned = result.tilesScanned,
            leafDirectoriesRead = result.leafDirectoriesRead,
            leafDirectoriesSkipped = result.leafDirectoriesSkipped,
            outputLeafDirectories = result.write.leafDirectories,
            tileReads = result.tileReads,
            tileBytesRead = result.tileBytesRead,
            rangeRequests = (raw as? CachedHttpRangeSource)?.fetches ?: source.reads,
            rangeRequestBytes = source.bytes,
            check = check,
            timingsMs = timings.ms,
        )
        echo(reportJson.encodeToString(report))
    }

    /** Copies the input metadata with bounds and zooms set to the extract's (TileJSON field names). */
    private fun patchMetadata(text: String, box: BoundingBox, zMin: Int, zMax: Int): String {
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return text
        val patched = LinkedHashMap<String, JsonElement>(parsed)
        patched["bounds"] = JsonArray(listOf(box.west, box.south, box.east, box.north).map { JsonPrimitive(it) })
        // Keep the type the input used for zooms (some writers use strings, TileJSON uses numbers).
        val zoomAsString = (parsed["minzoom"] as? JsonPrimitive)?.isString ?: false
        patched["minzoom"] = if (zoomAsString) JsonPrimitive(zMin.toString()) else JsonPrimitive(zMin)
        patched["maxzoom"] = if (zoomAsString) JsonPrimitive(zMax.toString()) else JsonPrimitive(zMax)
        patched.remove("center")
        return Json.encodeToString(JsonObject.serializer(), JsonObject(patched))
    }

    /** Re-reads the written archive: every tile inside the box, and every tile of the box the input has is present. */
    private fun check(input: PmtilesReader, box: BoundingBox, zMin: Int, zMax: Int): ExtractCheck {
        val written = PmtilesReader.open(FileRangeSource(out), Gzip.decompressors)
        // Tile ranges of the box per zoom, the same rule as WebMercator.tilesCovering.
        val ranges = (zMin..zMax).associateWith { z ->
            val max = (1 shl z) - 1
            fun clamp(v: Double) = floor(v).toInt().coerceIn(0, max)
            listOf(clamp(WebMercator.lonToX(box.west, z)), clamp(WebMercator.lonToX(box.east, z)),
                clamp(WebMercator.latToY(box.north, z)), clamp(WebMercator.latToY(box.south, z)))
        }
        val byZoom = sortedMapOf<Int, Long>()
        var outside = 0L
        for (e in written.tileEntries()) for (k in 0 until e.runLength) {
            val t = TileIds.toZxy(e.tileId + k)
            byZoom.merge(t.z, 1L, Long::plus)
            val r = ranges[t.z]
            if (r == null || t.x !in r[0]..r[1] || t.y !in r[2]..r[3]) outside++
        }
        // Tiles of the box the input has but the output lacks; skipped when the box has too many tiles to enumerate.
        val candidates = ranges.values.sumOf { (it[1] - it[0] + 1).toLong() * (it[3] - it[2] + 1) }
        val missing = if (candidates > 1 shl 20) null else ranges.entries.sumOf { (z, r) ->
            var n = 0L
            for (y in r[2]..r[3]) for (x in r[0]..r[1]) {
                if (input.findEntry(z, x, y) != null && written.findEntry(z, x, y) == null) n++
            }
            n
        }
        return ExtractCheck(byZoom.mapKeys { it.key.toString() }, outside, missing)
    }
}
