package io.github.jasondorjeshort.dystopo.devtool

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.tiles.ArgbImage
import io.github.jasondorjeshort.dystopo.tiles.ContourTileOptions
import io.github.jasondorjeshort.dystopo.tiles.ContourTilePlan
import io.github.jasondorjeshort.dystopo.tiles.ContourTiles
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import io.github.jasondorjeshort.dystopo.tiles.ImageDecoder
import java.io.File
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.math.roundToLong

/** `devtool contours ...`: contour tile tools. */
class ContoursCommand : CliktCommand(name = "contours") {
    override fun help(context: Context) = "Contour tile tools (shared ContourTiles)."

    init {
        subcommands(ContoursBenchCommand())
    }

    override fun run() = Unit
}

/**
 * `devtool contours bench`: the on-device contour path ([ContourTiles.build]) timed single-threaded on
 * the JVM. Samples a block of tiles per zoom around a point from a DEM pack (sampling is timed
 * separately and excluded from the sweep), warms the JIT, then builds every tile `--runs` times.
 * Reports the best and median sweep and per-zoom min/avg/max (best run per tile) with the
 * trace/cut/simplify/encode split, plus CRC32s of the tile bytes so two builds can be checked for
 * byte-identical output.
 */
class ContoursBenchCommand : CliktCommand(name = "bench") {
    override fun help(context: Context) =
        "Time contour tile generation single-threaded: 2x2 tiles per zoom around a point; prints per-zoom ms and an output checksum"

    private val dem by option("--dem", help = "Terrarium DEM PMTiles").default("out/rainier-dem.pmtiles")
    private val lat by option("--lat", help = "centre latitude (default: Rainier summit)").double().default(46.853)
    private val lon by option("--lon", help = "centre longitude").double().default(-121.760)
    private val minZoom by option("--min-zoom", help = "lowest contour tile zoom").int().default(9)
    private val maxZoom by option("--max-zoom", help = "highest contour tile zoom").int().default(15)
    private val tilesAcross by option("--tiles", help = "tiles per side per zoom (2 = 4 tiles per zoom)").int().default(2)
    private val warmup by option("--warmup", help = "untimed sweeps first (JIT warm-up)").int().default(5)
    private val runs by option("--runs", help = "timed sweeps; per tile the best run is reported").int().default(10)

    private class Job(val plan: ContourTilePlan, val grid: ElevationGrid, val sampleMs: Double)

    override fun run() {
        val reader = PmtilesReader.open(FileRangeSource(File(dem)), Gzip.decompressors)
        val decoder = ImageDecoder { bytes ->
            val img = ImageIO.read(bytes.inputStream())
            ArgbImage(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
        }
        val h = reader.header
        val pyramid = DemPyramid(h.minZoom, h.maxZoom, decoder, cacheTiles = 256) { z, x, y -> reader.tile(z, x, y) }
        val options = ContourTileOptions()

        val jobs = ArrayList<Job>()
        for (z in minZoom..maxZoom) {
            // The tile holding the point, then its neighbours east and south.
            val x0 = floor(WebMercator.lonToX(lon, z) - (tilesAcross - 1) / 2.0).toInt()
            val y0 = floor(WebMercator.latToY(lat, z) - (tilesAcross - 1) / 2.0).toInt()
            for (dy in 0 until tilesAcross) for (dx in 0 until tilesAcross) {
                val t0 = System.nanoTime()
                val plan = ContourTiles.plan(z, x0 + dx, y0 + dy, options, pyramid.tileSize)
                val grid = ContourTiles.sample(pyramid, plan)
                jobs += Job(plan, grid, (System.nanoTime() - t0) / 1e6)
            }
        }

        repeat(warmup) { for (j in jobs) ContourTiles.build(j.grid, j.plan, options) }

        val best = DoubleArray(jobs.size) { Double.POSITIVE_INFINITY }
        val split = Array(jobs.size) { DoubleArray(4) }
        val sweeps = ArrayList<Double>()
        val crc = CRC32()
        val zoomCrc = HashMap<Int, CRC32>()
        val lines = IntArray(jobs.size)
        val points = IntArray(jobs.size)
        val bytes = IntArray(jobs.size)
        repeat(runs) { run ->
            val s0 = System.nanoTime()
            for ((i, j) in jobs.withIndex()) {
                val t0 = System.nanoTime()
                val tile = ContourTiles.build(j.grid, j.plan, options)
                val ms = (System.nanoTime() - t0) / 1e6
                if (ms < best[i]) {
                    best[i] = ms
                    val t = tile.timings!!
                    split[i] = doubleArrayOf(t.traceMs, t.cutMs, t.simplifyMs, t.encodeMs)
                }
                if (run == 0) {
                    crc.update(tile.bytes)
                    zoomCrc.getOrPut(j.plan.z) { CRC32() }.update(tile.bytes)
                    lines[i] = tile.lines
                    points[i] = tile.points
                    bytes[i] = tile.bytes.size
                }
            }
            sweeps += (System.nanoTime() - s0) / 1e6
        }

        val byZoom = LinkedHashMap<String, Any?>()
        for (z in minZoom..maxZoom) {
            val idx = jobs.indices.filter { jobs[it].plan.z == z }
            val ms = idx.map { best[it] }
            fun avgOf(k: Int) = r1(idx.sumOf { split[it][k] } / idx.size)
            byZoom[z.toString()] = linkedMapOf(
                "tiles" to idx.size,
                "grid" to jobs[idx.first()].plan.windowSize,
                "minMs" to r1(ms.min()), "avgMs" to r1(ms.average()), "maxMs" to r1(ms.max()),
                "avgTraceMs" to avgOf(0), "avgCutMs" to avgOf(1), "avgSimplifyMs" to avgOf(2), "avgEncodeMs" to avgOf(3),
                "avgSampleMs" to r1(idx.sumOf { jobs[it].sampleMs } / idx.size),
                "lines" to idx.sumOf { lines[it] }, "points" to idx.sumOf { points[it] }, "bytes" to idx.sumOf { bytes[it] },
                "crc32" to "%08x".format(zoomCrc.getValue(z).value),
            )
        }
        val report = linkedMapOf(
            "dem" to dem,
            "center" to listOf(lat, lon),
            "zooms" to listOf(minZoom, maxZoom),
            "tiles" to jobs.size,
            "warmupSweeps" to warmup,
            "runs" to runs,
            "sweepBestMs" to r1(sweeps.min()),
            "sweepMedianMs" to r1(sweeps.sorted()[sweeps.size / 2]),
            "sumOfTileBestMs" to r1(best.sum()),
            "outputCrc32" to "%08x".format(crc.value),
            "byZoom" to byZoom,
        )
        println(JsonWriter.write(report))
    }

    private fun r1(d: Double) = (d * 10).roundToLong() / 10.0
}
