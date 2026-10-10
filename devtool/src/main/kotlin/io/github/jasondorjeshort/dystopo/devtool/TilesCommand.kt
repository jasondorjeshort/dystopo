package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.dem.Terrarium
import io.github.jasondorjeshort.dystopo.dem.TileWindow
import io.github.jasondorjeshort.dystopo.dem.TileWindows
import io.github.jasondorjeshort.dystopo.geo.BoundingBox
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.TileId
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.math.abs

/** Checks done when `--verify` is on: every written tile read back, and halo overlaps compared between neighbours. */
@Serializable
data class VerifyStats(
    val tilesReadBack: Int,
    /** Largest |decoded - rendered| over all read-back cells; Terrarium precision is 1/256 m. */
    val maxDecodeErrorMeters: Double,
    val neighborPairs: Int,
    val overlapCells: Long,
    /** Cells where one tile's halo render differs (in any bit) from the neighbour's own render. */
    val mismatchCells: Long,
    val maxMismatchMeters: Double,
    /** [GridDigest] of every written tile decoded back from its PNG; equal across XYZ and PMTiles exports of the same tiles. */
    val gridDigest: String? = null,
)

@Serializable
data class ZoomReport(
    val zoom: Int,
    val tiles: Int,
    val written: Int,
    val skippedNoData: Int,
    val bytes: Long,
    val halo: Int,
    val windowPx: Int,
    val cellSizeMeters: Double,
    val featherCells: Double,
    val noDataCells: Long,
    val elevationMinMeters: Double,
    val elevationMaxMeters: Double,
    val timeMs: Long,
    /** Summed over tiles (so across threads); larger than timeMs when tiles run in parallel. */
    val fetchMsTotal: Long,
    val encodeMsTotal: Long,
    val sourceCounterSums: Map<String, Long>,
    val verify: VerifyStats? = null,
)

@Serializable
data class TilesReport(
    val area: String?,
    val bbox: List<Double>,
    val source: String,
    val output: String,
    val minZoom: Int,
    val maxZoom: Int,
    val tileSize: Int,
    val threads: Int,
    val zooms: List<ZoomReport>,
    val totalTiles: Int,
    val totalWritten: Int,
    val totalBytes: Long,
    val totalTimeMs: Long,
    val preview: String? = null,
    /** Data source registry ids that contributed to any written tile (see `devtool sources`). */
    val provenance: List<String>,
    val attribution: String,
    /** Present with `--pmtiles`: the archive written and, with `--verify`, its read-back check. */
    val pmtiles: PmtilesOutputReport? = null,
)

/** Written next to the tiles as metadata.json; a TileJSON-like description for consumers (MapLibre, PMTiles packaging). */
@Serializable
data class TileSetMetadata(
    val name: String,
    val format: String = "png",
    val encoding: String = "terrarium",
    val scheme: String = "xyz",
    val tileSize: Int,
    val bounds: List<Double>,
    val minzoom: Int,
    val maxzoom: Int,
    val verticalDatum: String = "EGM2008",
    val noDataMeters: Double = 0.0,
    val source: String,
    /** Composed from the data source registry for [provenance]. */
    val attribution: String,
    val attributionHtml: String,
    val provenance: List<String>,
)

class TilesCommand : CliktCommand(name = "tiles") {
    override fun help(context: Context) =
        "Write an elevation source as Terrarium-encoded PNG tiles, <out>/{z}/{x}/{y}.png and/or a PMTiles archive (--pmtiles), for a zoom range. " +
            "Each tile is rendered through its own window with a halo so feathered seams match across tile borders. Prints a JSON report."

    private val area by option("--area", help = "Named area (see 'devtool areas')")
    private val bbox by option("--bbox", help = "west,south,east,north in degrees (instead of --area)")
    private val minZoom by option("--min-zoom").int().required()
    private val maxZoom by option("--max-zoom").int().required()
    private val source by option("--source", help = "Elevation source").choice(*ElevationSource.ids.toTypedArray()).default("best")
    private val out by option("--out", help = "Tile directory (default out/<area>-<source>; with --pmtiles, written only when given)").file()
    private val pmtiles by option("--pmtiles", help = "Also (or, without --out, only) write the tiles into this PMTiles archive").file(canBeDir = false)
    private val threads by option("--threads", help = "Tiles rendered in parallel (default: CPU count)").int()
        .default(Runtime.getRuntime().availableProcessors())
    private val verify by option("--verify", help = "Read every tile back and compare halo overlaps between neighbours (holds one zoom's grids in memory)").flag()
    private val previewZoom by option("--preview-zoom", help = "Also stitch this zoom's written tiles into a hillshade+contour PNG").int()
    private val style by StyleOptions()

    private class TileResult(
        val tile: TileId,
        val haloed: ElevationGrid?,
        val skipped: Boolean,
        val bytes: Int,
        val noDataCells: Long,
        val min: Float,
        val max: Float,
        val fetchMs: Long,
        val encodeMs: Long,
        val counters: Map<String, Long>,
        val decodeError: Float,
        val provenance: List<String>,
        /** PNG bytes, kept for the PMTiles archive. */
        val png: ByteArray? = null,
        /** [GridDigest] of the decoded PNG (verify only). */
        val gridDigest: Long = 0L,
    )

    private val writeXyz: Boolean get() = pmtiles == null || out != null

    override fun run() {
        if (minZoom > maxZoom || minZoom < 0 || maxZoom > 22) throw UsageError("need 0 <= --min-zoom <= --max-zoom <= 22")
        if (threads < 1) throw UsageError("--threads must be >= 1")
        val (name, box) = Areas.resolve(area, bbox)
        val src = ElevationSource.byId(source)
        val outDir = out ?: File("out/${name ?: "bbox"}-$source")
        val feather = featherMeters(src)
        val tileSize = 256

        val started = System.nanoTime()
        val pool = Executors.newFixedThreadPool(threads)
        val zooms = ArrayList<ZoomReport>()
        val provenanceSeen = HashSet<String>()
        val archive = pmtiles?.let { DemPmtilesWriter(it) }
        val expectedDigests = HashMap<TileId, Long>()
        try {
            for (z in minZoom..maxZoom) zooms += exportZoom(z, box, src, feather, outDir, tileSize, pool, provenanceSeen, archive, expectedDigests)
        } catch (e: Throwable) {
            archive?.close()
            throw e
        } finally {
            pool.shutdown()
        }
        val provenance = ElevationSource.mergeProvenance(provenanceSeen.toList())
        // Credit what went into the written tiles; if nothing was written, credit the whole source.
        val creditIds = provenance.ifEmpty { src.registryIds }

        val archiveReport = archive?.use { a ->
            val file = pmtiles!!
            if (zooms.sumOf { it.written } == 0) throw UsageError("no tiles with data in the box; nothing to put in ${file.path}")
            val written = a.finish(tileSetMetadata(file.nameWithoutExtension, box, tileSize, provenance, creditIds), box, minZoom, maxZoom)
            if (!verify) written
            else written.copy(verify = DemPmtilesWriter.verify(file, expectedDigests) { png -> readTile(png, 1.0).values })
        }

        if (writeXyz) {
            outDir.mkdirs()
            File(outDir, "metadata.json").writeText(
                reportJson.encodeToString(tileSetMetadata(outDir.name, box, tileSize, provenance, creditIds)),
            )
        }

        val preview = previewZoom?.let { z ->
            if (z !in minZoom..maxZoom) throw UsageError("--preview-zoom must be within the exported range")
            val load: (TileId) -> ByteArray? = if (writeXyz) {
                { t -> File(outDir, "${t.z}/${t.x}/${t.y}.png").takeIf { it.exists() }?.readBytes() }
            } else {
                val reader = PmtilesReader.open(FileRangeSource(pmtiles!!))
                ({ t -> reader.tile(t.z, t.x, t.y) })
            }
            val previewBase = if (writeXyz) outDir else File(pmtiles!!.absoluteFile.parentFile, pmtiles!!.nameWithoutExtension)
            renderPreview(z, box, previewBase, tileSize, TileWindows.referenceCellSizeMeters(box, z, tileSize), load)
        }

        val report = TilesReport(
            area = name,
            bbox = listOf(box.west, box.south, box.east, box.north),
            source = source,
            output = if (writeXyz) outDir.path else pmtiles!!.path,
            minZoom = minZoom,
            maxZoom = maxZoom,
            tileSize = tileSize,
            threads = threads,
            zooms = zooms,
            totalTiles = zooms.sumOf { it.tiles },
            totalWritten = zooms.sumOf { it.written },
            totalBytes = zooms.sumOf { it.bytes },
            totalTimeMs = (System.nanoTime() - started) / 1_000_000,
            preview = preview,
            provenance = provenance,
            attribution = SourceRegistry.builtIn.attribution(creditIds),
            pmtiles = archiveReport,
        )
        echo(reportJson.encodeToString(report))
    }

    private fun tileSetMetadata(name: String, box: BoundingBox, tileSize: Int, provenance: List<String>, creditIds: List<String>) =
        TileSetMetadata(
            name = name, tileSize = tileSize, bounds = listOf(box.west, box.south, box.east, box.north),
            minzoom = minZoom, maxzoom = maxZoom, source = source,
            attribution = SourceRegistry.builtIn.attribution(creditIds),
            attributionHtml = SourceRegistry.builtIn.attributionHtml(creditIds),
            provenance = provenance,
        )

    /** Widest blend band the source uses; the halo is sized from it. */
    private fun featherMeters(src: ElevationSource): Double = when (src) {
        is BestSource -> BestSource.GLOBAL_FEATHER_METERS
        is FeatheredSource -> LidarSource.FEATHER_METERS
        else -> 0.0
    }

    private fun exportZoom(
        z: Int, box: BoundingBox, src: ElevationSource, feather: Double, outDir: File, tileSize: Int,
        pool: ExecutorService, provenanceSeen: MutableSet<String>,
        archive: DemPmtilesWriter? = null, expectedDigests: MutableMap<TileId, Long> = HashMap(),
    ): ZoomReport {
        val started = System.nanoTime()
        // One cell size per zoom so every tile's blend band (and halo) is the same; see TileWindows.
        val cellSize = TileWindows.referenceCellSizeMeters(box, z, tileSize)
        val plan = TileWindows.forBbox(box, z, feather, tileSize)
        val fetch: (MercatorWindow) -> ElevationSource.Result =
            if (src is FeatheredSource) { w -> src.fetch(w, cellSize) } else { w -> src.fetch(w) }

        val tasks = plan.map { tw -> Callable { renderTile(tw, fetch, outDir) } }
        val results = try {
            pool.invokeAll(tasks).map { it.get() }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }

        val counterSums = LinkedHashMap<String, Long>()
        for (r in results) for ((k, v) in r.counters) counterSums[k] = (counterSums[k] ?: 0L) + v
        val written = results.filter { !it.skipped }
        written.forEach { provenanceSeen += it.provenance }
        // Added on this thread in plan order; the writer sorts by tile ID anyway.
        if (archive != null) for (r in written) archive.add(r.tile, r.png!!)
        if (verify) for (r in written) expectedDigests[r.tile] = r.gridDigest
        val verifyStats = if (verify) {
            verifySeams(plan, results).copy(gridDigest = GridDigest.hex(written.sumOf { it.gridDigest }))
        } else null

        return ZoomReport(
            zoom = z,
            tiles = plan.size,
            written = written.size,
            skippedNoData = results.size - written.size,
            bytes = written.sumOf { it.bytes.toLong() },
            halo = plan.firstOrNull()?.halo ?: 0,
            windowPx = plan.firstOrNull()?.window?.width ?: 0,
            cellSizeMeters = round2(cellSize),
            featherCells = round2(feather / cellSize),
            noDataCells = results.sumOf { it.noDataCells },
            elevationMinMeters = written.minOfOrNull { it.min }?.toDouble() ?: Double.NaN,
            elevationMaxMeters = written.maxOfOrNull { it.max }?.toDouble() ?: Double.NaN,
            timeMs = (System.nanoTime() - started) / 1_000_000,
            fetchMsTotal = results.sumOf { it.fetchMs },
            encodeMsTotal = results.sumOf { it.encodeMs },
            sourceCounterSums = counterSums,
            verify = verifyStats,
        )
    }

    private fun renderTile(tw: TileWindow, fetch: (MercatorWindow) -> ElevationSource.Result, outDir: File): TileResult {
        val t0 = System.nanoTime()
        val fetched = fetch(tw.window)
        val t1 = System.nanoTime()
        val grid = tw.interior(fetched.grid)
        val noData = grid.values.count { it.isNaN() }.toLong()
        if (noData == grid.values.size.toLong()) {
            return TileResult(tw.tile, if (verify) fetched.grid else null, true, 0, noData, Float.NaN, Float.NaN,
                (t1 - t0) / 1_000_000, 0, fetched.counters, 0f, fetched.provenance)
        }

        val bytes = encodePng(TileWindows.encodeTerrarium(grid), grid.width, grid.height)
        if (writeXyz) {
            val file = File(outDir, "${tw.tile.z}/${tw.tile.x}/${tw.tile.y}.png")
            file.parentFile.mkdirs()
            file.writeBytes(bytes)
        }
        val t2 = System.nanoTime()

        var decodeError = 0f
        var digest = 0L
        if (verify) {
            val back = readTile(bytes, grid.cellSizeMeters)
            for (i in grid.values.indices) {
                val expected = grid.values[i].let { if (it.isNaN()) 0f else it }
                decodeError = maxOf(decodeError, abs(back.values[i] - expected))
            }
            digest = GridDigest.tile(tw.tile, back.values)
        }
        return TileResult(
            tw.tile, if (verify) fetched.grid else null, false, bytes.size, noData, grid.min(), grid.max(),
            (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, fetched.counters, decodeError, fetched.provenance,
            png = if (pmtiles != null) bytes else null, gridDigest = digest,
        )
    }

    private fun encodePng(argb: IntArray, width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, width, height, argb, 0, width)
        val buffer = ByteArrayOutputStream(width * height)
        check(ImageIO.write(image, "png", buffer)) { "no PNG writer" }
        return buffer.toByteArray()
    }

    private fun readTile(bytes: ByteArray, cellSizeMeters: Double): ElevationGrid {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("unreadable PNG")
        val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        return Terrarium.decode(argb, image.width, image.height, cellSizeMeters)
    }

    /**
     * For every pair of neighbouring tiles, the cells of A's haloed render that fall inside B's tile
     * must equal B's own render of them. Any difference means the halo is too small for the blend
     * band or a source is not window-independent.
     */
    private fun verifySeams(plan: List<TileWindow>, results: List<TileResult>): VerifyStats {
        val byTile = results.associateBy { it.tile }
        val windows = plan.associateBy { it.tile }
        var pairs = 0
        var overlap = 0L
        var mismatches = 0L
        var maxDiff = 0f
        for (a in plan) {
            val ga = byTile.getValue(a.tile).haloed ?: continue
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val bId = TileId(a.tile.z, a.tile.x + dx, a.tile.y + dy)
                val b = windows[bId] ?: continue
                val gb = byTile.getValue(bId).haloed ?: continue
                pairs++
                // Global pixel range of B's tile interior intersected with A's window.
                val x0 = maxOf(a.window.x0, b.tile.x * b.tileSize)
                val x1 = minOf(a.window.x0 + a.window.width, (b.tile.x + 1) * b.tileSize)
                val y0 = maxOf(a.window.y0, b.tile.y * b.tileSize)
                val y1 = minOf(a.window.y0 + a.window.height, (b.tile.y + 1) * b.tileSize)
                for (gy in y0 until y1) for (gx in x0 until x1) {
                    val va = ga[gx - a.window.x0, gy - a.window.y0]
                    val vb = gb[gx - b.window.x0, gy - b.window.y0]
                    overlap++
                    if (va.toBits() != vb.toBits()) {
                        mismatches++
                        if (!va.isNaN() && !vb.isNaN()) maxDiff = maxOf(maxDiff, abs(va - vb)) else maxDiff = Float.POSITIVE_INFINITY
                    }
                }
            }
        }
        return VerifyStats(
            tilesReadBack = results.count { !it.skipped },
            maxDecodeErrorMeters = results.maxOfOrNull { it.decodeError }?.toDouble() ?: 0.0,
            neighborPairs = pairs,
            overlapCells = overlap,
            mismatchCells = mismatches,
            maxMismatchMeters = maxDiff.toDouble(),
        )
    }

    /** Stitches the written tiles of zoom [z] back into one grid (NaN where no tile was written) and renders it. */
    private fun renderPreview(
        z: Int, box: BoundingBox, outDir: File, tileSize: Int, cellSize: Double,
        load: (TileId) -> ByteArray? = { t -> File(outDir, "${t.z}/${t.x}/${t.y}.png").takeIf { it.exists() }?.readBytes() },
    ): String {
        val tiles = TileWindows.forBbox(box, z, 0.0, tileSize).map { it.tile }
        val tx0 = tiles.minOf { it.x }
        val ty0 = tiles.minOf { it.y }
        val cols = tiles.maxOf { it.x } - tx0 + 1
        val rows = tiles.maxOf { it.y } - ty0 + 1
        val w = cols * tileSize
        val h = rows * tileSize
        check(w <= 8192 && h <= 8192) { "preview at z$z would be ${w}x$h px; pick a smaller zoom" }
        val values = FloatArray(w * h) { Float.NaN }
        for (t in tiles) {
            val grid = readTile(load(t) ?: continue, cellSize)
            val ox = (t.x - tx0) * tileSize
            val oy = (t.y - ty0) * tileSize
            for (j in 0 until tileSize) grid.values.copyInto(values, (oy + j) * w + ox, j * tileSize, (j + 1) * tileSize)
        }
        val stitched = ElevationGrid(w, h, cellSize, values)
        val (image, _) = MapRenderer.render(stitched, z, style, Timings())
        val file = File(outDir.absoluteFile.parentFile, "${outDir.name}-preview-z$z.png")
        ImageIO.write(image, "png", file)
        return file.path
    }

    private fun round2(x: Double) = Math.round(x * 100) / 100.0
}
