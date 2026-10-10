package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.dem.Datums
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.Utm
import io.github.jasondorjeshort.dystopo.tiff.ByteRangeSource
import io.github.jasondorjeshort.dystopo.tiff.GeoTiff
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor

/**
 * Kartverket's national detailed height model (DTM): bare earth of mainland Norway at 1 m, NN2000
 * heights on ETRS89 / UTM 33N (EPSG:25833), CC BY 4.0.
 *
 * Kartverket publishes no range-readable COGs (the hoydedata.no download is zipped project blocks),
 * but its WCS cuts any box at any resolution. So this source asks the WCS for fixed blocks of
 * [BLOCK] x [BLOCK] pixels on a UTM 33 grid at one of [RESOLUTIONS], chosen per window like an
 * overview level, and caches each block on disk as raw floats: a block is requested once ever, and
 * windows anywhere reuse it. Values of exactly 0 are the service's fill (sea, and land beyond a strip
 * along the Swedish, Finnish and Russian borders) and become NaN, so Copernicus fills there.
 * Heights are converted NN2000 -> EGM2008 with HREF2018B and EGM2008.
 */
class NorwaySource(private val cacheDir: File = Caches.dir("kartverket-dtm")) : ElevationSource {
    override val id = "kartverket"
    override val registryIds = listOf("kartverket-dtm", Datums.NORWAY_GEOID.registryId, "egm2008")

    override fun fetch(window: MercatorWindow): ElevationSource.Result {
        val box = NationalDem.bounds(window)
        if (!NationalDem.intersects(box, COVERAGE)) return NationalDem.nanResult(window, mapOf("outsideCoverage" to 1L))
        val targetMeters = OverviewLevels.targetMeters(window)
        if (!NationalDem.useful(window)) return NationalDem.nanResult(window, mapOf("skippedCoarse" to 1L))
        val res = resolutionFor(targetMeters)

        // Project every output pixel to UTM 33 once.
        val w = window.width
        val h = window.height
        val xs = DoubleArray(w * h)
        val ys = DoubleArray(w * h)
        val lons = DoubleArray(w) { window.lonAt(it) }
        for (j in 0 until h) {
            val lat = window.latAt(j)
            for (i in 0 until w) {
                val p = Utm.forward(lat, lons[i], ZONE)
                xs[j * w + i] = p.easting
                ys[j * w + i] = p.northing
            }
        }

        // Every block holding one of the four pixels a bilinear sample reads.
        val needed = LinkedHashSet<Long>()
        for (k in xs.indices) {
            val c0 = floor(xs[k] / res - 0.5).toLong()
            val r0 = floor(ys[k] / res - 0.5).toLong()
            for (c in c0..c0 + 1) for (r in r0..r0 + 1) {
                if (!insideEnvelope((c * res).toDouble(), (r * res).toDouble())) continue
                needed += key(Math.floorDiv(c, BLOCK.toLong()), Math.floorDiv(r, BLOCK.toLong()))
            }
        }
        val readStarted = System.nanoTime()
        val requests = AtomicLong()
        val cachedBefore = AtomicLong()
        val blocks = HashMap<Long, FloatArray>()
        io.map(needed) { k -> k to block(res, bx(k), by(k), requests, cachedBefore) }.forEach { (k, v) -> blocks[k] = v }
        val readMs = (System.nanoTime() - readStarted) / 1_000_000

        fun pixel(c: Long, r: Long): Float {
            val b = blocks[key(Math.floorDiv(c, BLOCK.toLong()), Math.floorDiv(r, BLOCK.toLong()))] ?: return Float.NaN
            val ci = Math.floorMod(c, BLOCK.toLong()).toInt()
            val ri = BLOCK - 1 - Math.floorMod(r, BLOCK.toLong()).toInt() // rows run north to south
            return b[ri * BLOCK + ci]
        }

        val values = FloatArray(w * h) { Float.NaN }
        for (k in xs.indices) {
            val col = xs[k] / res - 0.5
            val row = ys[k] / res - 0.5
            val c0 = floor(col).toLong()
            val r0 = floor(row).toLong()
            val fx = (col - c0).toFloat()
            val fy = (row - r0).toFloat()
            val a = pixel(c0, r0)
            val b = pixel(c0 + 1, r0)
            val c = pixel(c0, r0 + 1)
            val d = pixel(c0 + 1, r0 + 1)
            // NaN propagates: a cell next to fill or the coverage edge is left to the fallback.
            values[k] = (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
        }

        // NN2000 -> EGM2008.
        val any = values.any { !it.isNaN() }
        val (meanOffset, converted, unconverted) =
            if (any) NationalDem.applyOffsets(values, NationalDem.offsets(window, Datums.NORWAY_GEOID)) else Triple(0.0, 0L, 0L)

        val grid = ElevationGrid(w, h, window.cellSizeMeters, values)
        val counters = LinkedHashMap<String, Long>()
        counters["resolutionMeters"] = res.toLong()
        counters["blocksUsed"] = needed.size.toLong()
        counters["blocksCachedOnDisk"] = cachedBefore.get()
        counters["wcsRequests"] = requests.get()
        counters["readMs"] = readMs
        counters["datumOffsetMeanCm"] = Math.round(meanOffset * 100)
        counters["datumUnconvertedCells"] = unconverted
        counters["coveragePermille"] = (grid.values.count { !it.isNaN() } * 1000L) / grid.values.size
        counters["noDataCells"] = grid.values.count { it.isNaN() }.toLong()
        val ids = if (converted > 0) registryIds else emptyList()
        return ElevationSource.Result(grid, counters, ElevationSource.provenanceIfData(grid, ids))
    }

    /** One block's floats (row-major, north row first, 0 fill as NaN): from the disk cache, else from the WCS. */
    private fun block(res: Int, bx: Long, by: Long, requests: AtomicLong, cachedBefore: AtomicLong): FloatArray {
        val file = File(cacheDir, "blocks/r$res/$bx/$bx-$by.f32")
        if (file.exists() && file.length() == BLOCK * BLOCK * 4L) {
            cachedBefore.incrementAndGet()
            return decodeFloats(file.readBytes())
        }
        val minX = bx * BLOCK * res
        val minY = by * BLOCK * res
        val url = blockUrl(minX, minY, res)
        val tmp = File(cacheDir, "blocks/r$res/$bx/$bx-$by.${ProcessHandle.current().pid()}-${Thread.currentThread().threadId()}.tif")
        val floats = LidarSource.retryTnm {
            try {
                Http.download(url, tmp)
                requests.incrementAndGet()
                parseBlock(tmp.readBytes(), minX.toDouble(), (minY + BLOCK * res).toDouble(), res.toDouble())
            } finally {
                tmp.delete()
            }
        }
        file.parentFile.mkdirs()
        val part = Http.partFile(file)
        part.writeBytes(encodeFloats(floats))
        Http.publish(part, file)
        return floats
    }

    companion object {
        const val WCS = "https://wcs.geonorge.no/skwms1/wcs.hoyde-dtm-nhm-25833"
        const val COVERAGE_ID = "nhm_dtm_topo_25833"
        const val ZONE = 33

        /** Block edge in pixels: 256 x 256 float32 is 262 kB uncompressed (the WCS sends no compression). */
        const val BLOCK = 256

        /** Block pixel sizes, metres; a window reads the coarsest one no larger than its cells. */
        val RESOLUTIONS = intArrayOf(1, 2, 4, 8, 16)

        /** Mainland Norway with margin: west, south, east, north (the HREF2018B grid is 4-32 E, 57.8-72 N). */
        val COVERAGE = doubleArrayOf(4.0, 57.8, 31.5, 71.5)

        /** The coverage's EPSG:25833 envelope (DescribeCoverage): minX, minY, maxX, maxY. */
        val ENVELOPE = doubleArrayOf(-100_275.0, 6_399_725.0, 1_150_255.0, 8_000_275.0)

        private val io = NationalDem.IoPool("kartverket-io")

        fun resolutionFor(targetMeters: Double): Int = RESOLUTIONS.lastOrNull { it <= targetMeters } ?: RESOLUTIONS.first()

        fun insideEnvelope(x: Double, y: Double): Boolean = x >= ENVELOPE[0] && x <= ENVELOPE[2] && y >= ENVELOPE[1] && y <= ENVELOPE[3]

        /** WCS 1.0.0 GetCoverage for one block; the bbox is the outer edge of its pixels, nearest-neighbour resampling. */
        fun blockUrl(minX: Long, minY: Long, res: Int): String {
            val size = BLOCK.toLong() * res
            return "$WCS?service=WCS&request=GetCoverage&version=1.0.0&coverage=$COVERAGE_ID&format=GeoTIFF&crs=EPSG:25833" +
                "&bbox=$minX,$minY,${minX + size},${minY + size}&width=$BLOCK&height=$BLOCK"
        }

        /**
         * A WCS response as BLOCK x BLOCK floats, row-major from the north, with the fill value 0 as NaN.
         * Checks the georeference against the request ([west], [north] edges, [res] pixel size).
         */
        fun parseBlock(bytes: ByteArray, west: Double, north: Double, res: Double): FloatArray {
            require(bytes.size > 8 && (bytes[0] == 'I'.code.toByte() || bytes[0] == 'M'.code.toByte())) {
                "Kartverket WCS did not return a TIFF: ${bytes.copyOf(minOf(200, bytes.size)).decodeToString()}"
            }
            val tiff = GeoTiff.open(ByteRangeSource { offset, length ->
                bytes.copyOfRange(offset.toInt().coerceAtMost(bytes.size), (offset + length).toInt().coerceAtMost(bytes.size))
            })
            check(tiff.width == BLOCK && tiff.height == BLOCK) { "WCS block is ${tiff.width}x${tiff.height}" }
            val e = tiff.extent
            check(kotlin.math.abs(e[0] - west) < 0.01 && kotlin.math.abs(e[3] - north) < 0.01 && kotlin.math.abs(tiff.pixelScaleX - res) < 1e-6) {
                "WCS block georeference ${e.toList()} / ${tiff.pixelScaleX} does not match the request ($west, $north, $res)"
            }
            val out = FloatArray(BLOCK * BLOCK)
            for (ty in 0 until tiff.tilesDown) for (tx in 0 until tiff.tilesAcross) {
                val t = tiff.readTile(tx, ty)
                for (r in 0 until tiff.tileHeight) {
                    val row = ty * tiff.tileHeight + r
                    if (row >= BLOCK) break
                    for (c in 0 until tiff.tileWidth) {
                        val col = tx * tiff.tileWidth + c
                        if (col >= BLOCK) break
                        val v = t[r * tiff.tileWidth + c]
                        val nd = tiff.noData
                        out[row * BLOCK + col] = if (v == 0f || v.isNaN() || (nd != null && v == nd) || v < -1000f) Float.NaN else v
                    }
                }
            }
            return out
        }

        private fun key(bx: Long, by: Long): Long = (bx shl 32) or (by and 0xFFFFFFFFL)

        private fun bx(key: Long): Long = key shr 32

        private fun by(key: Long): Long = key.toInt().toLong()

        private fun encodeFloats(values: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            buf.asFloatBuffer().put(values)
            return buf.array()
        }

        private fun decodeFloats(bytes: ByteArray): FloatArray {
            val out = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
            return out
        }
    }
}
