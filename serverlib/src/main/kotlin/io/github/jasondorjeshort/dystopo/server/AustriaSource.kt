package io.github.jasondorjeshort.dystopo.server

import io.github.jasondorjeshort.dystopo.dem.Datums
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.geo.LambertAzimuthalEqualArea
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import java.io.File
import kotlin.math.floor

/**
 * BEV ALS DTM: bare earth of Austria at 1 m from airborne laser scanning (a joint product of BEV and
 * the federal states), EVRF2000 Austria heights on ETRS89 / LAEA Europe (EPSG:3035), CC BY 4.0.
 *
 * BEV publishes 55 tiles of 50x50 km per yearly edition through its INSPIRE download service, each a
 * Cloud-Optimized BigTIFF of about 7.7 GB (512 px LZW tiles with the floating-point predictor and
 * internal overviews), so a window reads only the blocks it needs with range requests, from the
 * overview level that matches its cells; the tile list is fixed per edition ([TILES]). Output pixels
 * are projected with [LambertAzimuthalEqualArea.EUROPE] and sampled bilinearly. Heights are converted
 * EVRF2000 Austria -> EGM2008 with BEV's ETRS89 geoid and EGM2008.
 */
class AustriaSource(private val cacheDir: File = Caches.dir("bev-als-dtm")) : ElevationSource {
    override val id = "bev"
    override val registryIds = listOf("bev-als-dtm", Datums.AUSTRIA_GEOID.registryId, "egm2008")

    override fun fetch(window: MercatorWindow): ElevationSource.Result {
        val box = NationalDem.bounds(window)
        if (!NationalDem.intersects(box, COVERAGE)) return NationalDem.nanResult(window, mapOf("outsideCoverage" to 1L))
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
                val p = LambertAzimuthalEqualArea.EUROPE.forward(lat, lons[i])
                xs[j * w + i] = p.easting
                ys[j * w + i] = p.northing
            }
        }

        // The part of each needed tile the window covers, in EPSG:3035 metres.
        class Need(val tile: Pair<Int, Int>, var minX: Double, var minY: Double, var maxX: Double, var maxY: Double)
        val needs = HashMap<Pair<Int, Int>, Need>()
        for (k in xs.indices) {
            val t = tileOf(xs[k], ys[k]) ?: continue
            val need = needs.getOrPut(t) { Need(t, xs[k], ys[k], xs[k], ys[k]) }
            if (xs[k] < need.minX) need.minX = xs[k]
            if (xs[k] > need.maxX) need.maxX = xs[k]
            if (ys[k] < need.minY) need.minY = ys[k]
            if (ys[k] > need.maxY) need.maxY = ys[k]
        }
        if (needs.isEmpty()) return NationalDem.nanResult(window, mapOf("outsideTiles" to 1L))

        val readStarted = System.nanoTime()
        val opened = io.map(needs.keys) { t -> t to RemoteTiff(tileUrl(t.first, t.second), File(cacheDir, "tiles/$EDITION/${t.first}-${t.second}")) }.toMap()
        val levels = opened.mapValues { (_, tiff) -> tiff.levelFor(targetMeters, 1.0) }
        val blocks = ArrayList<() -> Unit>()
        for (need in needs.values) {
            val tiff = opened.getValue(need.tile)
            val level = levels.getValue(need.tile)
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
        val readMs = (System.nanoTime() - readStarted) / 1_000_000

        val values = FloatArray(w * h) { Float.NaN }
        for (k in xs.indices) {
            val t = tileOf(xs[k], ys[k]) ?: continue
            val tiff = opened[t] ?: continue
            values[k] = tiff.sample(levels.getValue(t), xs[k], ys[k])
        }

        // EVRF2000 Austria -> EGM2008.
        val any = values.any { !it.isNaN() }
        val (meanOffset, converted, unconverted) =
            if (any) NationalDem.applyOffsets(values, NationalDem.offsets(window, Datums.AUSTRIA_GEOID)) else Triple(0.0, 0L, 0L)

        val grid = ElevationGrid(w, h, window.cellSizeMeters, values)
        val counters = LinkedHashMap<String, Long>()
        counters["tilesUsed"] = opened.size.toLong()
        counters["overviewLevelMax"] = (levels.values.maxOrNull() ?: 0).toLong()
        counters["pixelMeters"] = opened.entries.maxOfOrNull { (t, tiff) -> tiff.level(levels.getValue(t)).pixelScaleX }?.toLong() ?: 0L
        counters["blocksDecoded"] = opened.values.sumOf { it.tilesDecoded }.toLong()
        counters["rangeRequests"] = opened.values.sumOf { it.source.fetches }
        counters["readMs"] = readMs
        counters["datumOffsetMeanCm"] = Math.round(meanOffset * 100)
        counters["datumUnconvertedCells"] = unconverted
        counters["coveragePermille"] = (grid.values.count { !it.isNaN() } * 1000L) / grid.values.size
        counters["noDataCells"] = grid.values.count { it.isNaN() }.toLong()
        val ids = if (converted > 0) registryIds else emptyList()
        return ElevationSource.Result(grid, counters, ElevationSource.provenanceIfData(grid, ids))
    }

    companion object {
        /** The edition (Stichtag) read: every edition from 2022 on is in EVRF2000 Austria heights. */
        const val EDITION = "20250915"

        const val BASE = "https://data.bev.gv.at/download/ALS/DTM"

        /** Tile edge, metres of EPSG:3035. */
        const val TILE_METERS = 50_000

        /** Austria with margin: west, south, east, north (the tiles' georss footprints span about 9.4-17.3 E, 46.2-49.1 N). */
        val COVERAGE = doubleArrayOf(9.4, 46.2, 17.3, 49.1)

        /**
         * The 55 tiles of the edition, as south-west corners in units of 50 km (northing to the
         * eastings present in that row), from the BEV ATOM feed of the series.
         */
        val TILES: Map<Int, IntRange> = mapOf(
            51 to 93..93,
            52 to 86..95,
            53 to 85..96,
            54 to 85..96,
            55 to 90..97,
            56 to 90..96,
            57 to 92..96,
        )

        private val io = NationalDem.IoPool("bev-io")

        /** The tile (north, east indices) holding EPSG:3035 point ([x], [y]), or null where BEV has none. */
        fun tileOf(x: Double, y: Double): Pair<Int, Int>? {
            val n = floor(y / TILE_METERS).toInt()
            val e = floor(x / TILE_METERS).toInt()
            return if (TILES[n]?.contains(e) == true) n to e else null
        }

        fun tileUrl(n: Int, e: Int): String =
            "$BASE/$EDITION/ALS_DTM_CRS3035RES50000mN${n * TILE_METERS}E${e * TILE_METERS}.tif"
    }
}
