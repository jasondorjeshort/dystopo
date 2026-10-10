package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.dem.Datums
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.geo.LambertConformalConic
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import java.io.File
import kotlin.math.floor

/**
 * NRCan's HRDEM mosaic: Canada's bare-earth lidar DTM, CGVD2013 heights on NAD83(CSRS) / Canada
 * Atlas Lambert (EPSG:3979), Open Government Licence - Canada, published on AWS (`canelevation-dem`)
 * as one Cloud-Optimized BigTIFF per 500 km tile of the EPSG:3979 grid, at 1 m and at 2 m (the 2 m
 * mosaic covers every lidar project, the 1 m mosaic those delivered at 1 m), LZW, overviews, sparse
 * where there is no lidar.
 *
 * Windows with cells of [ONE_METER_BELOW] or less read the 1 m mosaic and fill its holes from the 2 m
 * mosaic; coarser windows read the 2 m mosaic at the overview level matching their cells. The 1 m
 * files' tile index is about 15 MB (fetched once and cached on disk), which is why they are kept to
 * the deepest zooms. Output pixels are projected with [LambertConformalConic.CANADA_ATLAS] and sampled
 * bilinearly. Heights are converted CGVD2013 -> EGM2008 with CGG2013a, the NAD83 -> WGS84 height
 * shift, and EGM2008.
 */
class CanadaSource(private val cacheDir: File = Caches.dir("hrdem")) : ElevationSource {
    override val id = "hrdem"
    override val registryIds = listOf("hrdem", Datums.CANADA_GEOID.registryId, "egm2008")

    override fun fetch(window: MercatorWindow): ElevationSource.Result {
        val box = NationalDem.bounds(window)
        if (COVERAGE.none { NationalDem.intersects(box, it) }) return NationalDem.nanResult(window, mapOf("outsideCoverage" to 1L))
        // Read on this thread: tile exports override it per thread (see OverviewLevels).
        val targetMeters = OverviewLevels.targetMeters(window)
        if (!NationalDem.useful(window)) return NationalDem.nanResult(window, mapOf("skippedCoarse" to 1L))

        val w = window.width
        val h = window.height
        val xs = DoubleArray(w * h)
        val ys = DoubleArray(w * h)
        val lons = DoubleArray(w) { window.lonAt(it) }
        for (j in 0 until h) {
            val lat = window.latAt(j)
            for (i in 0 until w) {
                val p = LambertConformalConic.CANADA_ATLAS.forward(lat, lons[i])
                xs[j * w + i] = p.easting
                ys[j * w + i] = p.northing
            }
        }

        val values = FloatArray(w * h) { Float.NaN }
        val counters = LinkedHashMap<String, Long>()
        val readStarted = System.nanoTime()
        val products = if (targetMeters <= ONE_METER_BELOW) listOf(1, 2) else listOf(2)
        var tilesUsed = 0L
        for (res in products) {
            val read = read(res, targetMeters, xs, ys, values)
            tilesUsed += read.tiles
            counters["m$res.tiles"] = read.tiles.toLong()
            counters["m$res.overviewLevelMax"] = read.levelMax.toLong()
            counters["m$res.pixelMeters"] = read.pixelMeters.toLong()
            counters["m$res.blocksDecoded"] = read.blocks.toLong()
            counters["m$res.rangeRequests"] = read.requests
            counters["m$res.cells"] = read.cells
            if (values.none { it.isNaN() }) break
        }
        if (tilesUsed == 0L) return NationalDem.nanResult(window, mapOf("outsideTiles" to 1L))
        counters["readMs"] = (System.nanoTime() - readStarted) / 1_000_000

        // CGVD2013 -> EGM2008.
        val any = values.any { !it.isNaN() }
        val (meanOffset, converted, unconverted) =
            if (any) NationalDem.applyOffsets(values, NationalDem.offsets(window, Datums.CANADA_GEOID)) else Triple(0.0, 0L, 0L)

        val grid = ElevationGrid(w, h, window.cellSizeMeters, values)
        counters["datumOffsetMeanCm"] = Math.round(meanOffset * 100)
        counters["datumUnconvertedCells"] = unconverted
        counters["coveragePermille"] = (grid.values.count { !it.isNaN() } * 1000L) / grid.values.size
        counters["noDataCells"] = grid.values.count { it.isNaN() }.toLong()
        val ids = if (converted > 0) registryIds else emptyList()
        return ElevationSource.Result(grid, counters, ElevationSource.provenanceIfData(grid, ids))
    }

    private class Read(val tiles: Int, val levelMax: Int, val pixelMeters: Double, val blocks: Int, val requests: Long, val cells: Long)

    /** Samples the [res] m mosaic into the NaN cells of [values]. */
    private fun read(res: Int, targetMeters: Double, xs: DoubleArray, ys: DoubleArray, values: FloatArray): Read {
        class Need(var minX: Double, var minY: Double, var maxX: Double, var maxY: Double)
        val needs = HashMap<Pair<Int, Int>, Need>()
        for (k in xs.indices) {
            if (!values[k].isNaN()) continue
            val t = tileOf(res, xs[k], ys[k]) ?: continue
            val need = needs.getOrPut(t) { Need(xs[k], ys[k], xs[k], ys[k]) }
            if (xs[k] < need.minX) need.minX = xs[k]
            if (xs[k] > need.maxX) need.maxX = xs[k]
            if (ys[k] < need.minY) need.minY = ys[k]
            if (ys[k] > need.maxY) need.maxY = ys[k]
        }
        if (needs.isEmpty()) return Read(0, 0, 0.0, 0, 0, 0)
        val opened = io.map(needs.keys) { t -> t to RemoteTiff(tileUrl(res, t.first, t.second), File(cacheDir, "${res}m/${t.first}_${t.second}")) }.toMap()
        val levels = opened.mapValues { (_, tiff) -> tiff.levelFor(targetMeters, 1.0) }
        val blocks = ArrayList<() -> Unit>()
        for ((t, need) in needs) {
            val tiff = opened.getValue(t)
            val level = levels.getValue(t)
            val l = tiff.level(level)
            val c0 = (floor(l.colOf(need.minX)).toInt() - 1).coerceIn(0, l.width - 1)
            val c1 = (floor(l.colOf(need.maxX)).toInt() + 1).coerceIn(0, l.width - 1)
            val r0 = (floor(l.rowOf(need.maxY)).toInt() - 1).coerceIn(0, l.height - 1)
            val r1 = (floor(l.rowOf(need.minY)).toInt() + 1).coerceIn(0, l.height - 1)
            for (ty in r0 / l.tileHeight..r1 / l.tileHeight) for (tx in c0 / l.tileWidth..c1 / l.tileWidth) {
                blocks += { tiff.at(level, tx * l.tileWidth, ty * l.tileHeight) }
            }
        }
        io.map(blocks) { it() }
        var cells = 0L
        for (k in xs.indices) {
            if (!values[k].isNaN()) continue
            val t = tileOf(res, xs[k], ys[k]) ?: continue
            val tiff = opened[t] ?: continue
            val v = tiff.sample(levels.getValue(t), xs[k], ys[k])
            if (!v.isNaN()) {
                values[k] = v
                cells++
            }
        }
        return Read(
            tiles = opened.size,
            levelMax = levels.values.maxOrNull() ?: 0,
            pixelMeters = opened.entries.maxOf { (t, tiff) -> tiff.level(levels.getValue(t)).pixelScaleX },
            blocks = opened.values.sumOf { it.tilesDecoded },
            requests = opened.values.sumOf { it.source.fetches },
            cells = cells,
        )
    }

    companion object {
        const val BASE = "https://canelevation-dem.s3.ca-central-1.amazonaws.com"

        /** Tile edge, metres of EPSG:3979. */
        const val TILE_METERS = 500_000

        /** Output cells at or below this size (metres) read the 1 m mosaic first (z17 and deeper at Canadian latitudes). */
        const val ONE_METER_BELOW = 1.0

        /**
         * Where the source reads at all (west, south, east, north): Canada north of 48.2 N (Vancouver
         * Island's south tip), and southern Ontario, Quebec and the Maritimes down to Point Pelee. The
         * mosaic tiles reach far into the US, but its lidar is Canadian; these boxes keep US windows
         * such as Rainier from opening tile indexes for nothing.
         */
        val COVERAGE: List<DoubleArray> = listOf(
            doubleArrayOf(-141.1, 48.2, -52.5, 83.2),
            doubleArrayOf(-83.2, 41.6, -59.6, 48.2),
        )

        private fun tiles(list: String): Set<Pair<Int, Int>> =
            list.split(' ').map { it.split('_').let { (i, j) -> i.toInt() to j.toInt() } }.toSet()

        /**
         * Tiles of each mosaic, from the STAC collections hrdem-mosaic-1m (56 items) and hrdem-mosaic-2m
         * (64 items) at datacube.services.geo.ca, October 2026. A tile with no item has no lidar at all.
         */
        val TILES_1M: Set<Pair<Int, Int>> = tiles(
            "1_3 1_4 1_5 1_6 1_7 2_3 2_4 2_5 2_6 2_7 2_8 3_3 3_4 3_5 3_6 3_7 3_8 4_3 4_4 4_5 4_6 5_3 5_4 5_6 5_7 5_8 " +
                "6_2 6_3 6_5 6_6 6_7 6_8 7_2 7_3 7_4 7_5 7_6 7_7 7_8 8_1 8_2 8_3 8_4 8_5 8_6 8_7 9_2 9_3 9_4 9_5 " +
                "10_2 10_3 10_4 10_5 11_3 11_4",
        )
        val TILES_2M: Set<Pair<Int, Int>> = TILES_1M + tiles("4_7 4_8 4_9 5_5 5_9 6_9 6_10 9_6")

        private val io = NationalDem.IoPool("hrdem-io")

        /** The tile (i, j) of the [res] m mosaic holding EPSG:3979 point ([x], [y]), or null where there is none. */
        fun tileOf(res: Int, x: Double, y: Double): Pair<Int, Int>? {
            val t = floor(x / TILE_METERS).toInt() + 6 to floor(y / TILE_METERS).toInt() + 3
            return t.takeIf { it in (if (res == 1) TILES_1M else TILES_2M) }
        }

        fun tileUrl(res: Int, i: Int, j: Int): String = "$BASE/hrdem-mosaic-${res}m/${i}_$j-mosaic-${res}m-dtm.tif"
    }
}
