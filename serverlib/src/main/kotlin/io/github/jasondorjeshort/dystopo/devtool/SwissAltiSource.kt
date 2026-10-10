package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.dem.Datums
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.SwissProjection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor

/**
 * swisstopo swissALTI3D: bare-earth DTM of Switzerland and Liechtenstein, 2 m and 0.5 m, in 1x1 km
 * LV95 (EPSG:2056) tiles with LN02 heights, published as Cloud-Optimized GeoTIFFs through the
 * swisstopo STAC API.
 *
 * Tiles are found through the STAC items endpoint, asked once per 0.1° cell and cached on disk
 * (like the TNM index for lidar); where a tile has several survey years the newest wins. Output
 * pixels are projected to LV95 with [SwissProjection] and sampled bilinearly from the 2 m product,
 * or from the 0.5 m product where output cells are finer than 2 m. Heights are converted
 * LN02 -> EGM2008 with the CHGeo2004 grid and EGM2008 ([Datums.swissToEgm2008]). Cells outside
 * Switzerland are NaN, so this source is meant to be composited over a fallback (see [BestSource]).
 */
class SwissAltiSource(private val cacheDir: File = Caches.dir("swissalti3d")) : ElevationSource {
    override val id = "swissalti3d"
    override val registryIds = listOf("swissalti3d", "chgeo2004", "egm2008")

    /** One 1x1 km tile: south-west corner in km of LV95, survey year, asset URLs, vertical EPSG. */
    @Serializable
    data class Tile(val e: Int, val n: Int, val year: Int, val url2: String?, val url05: String?, val verticalEpsg: Int)

    @Serializable
    private data class CellIndex(val tiles: List<Tile>)

    private fun nanResult(window: MercatorWindow, counters: Map<String, Long>): ElevationSource.Result {
        val grid = ElevationGrid(window.width, window.height, window.cellSizeMeters, FloatArray(window.width * window.height) { Float.NaN })
        return ElevationSource.Result(grid, counters + ("noDataCells" to grid.values.size.toLong()), emptyList())
    }

    override fun fetch(window: MercatorWindow): ElevationSource.Result {
        val west = window.lonAt(0)
        val east = window.lonAt(window.width - 1)
        val north = window.latAt(0)
        val south = window.latAt(window.height - 1)
        if (!intersectsCoverage(west, south, east, north)) return nanResult(window, mapOf("outsideCoverage" to 1L))
        // Read on this thread: tile exports override it per thread (see OverviewLevels).
        val targetMeters = OverviewLevels.targetMeters(window)
        if (!useful(window)) return nanResult(window, mapOf("skippedCoarse" to 1L))
        val fine = targetMeters < FINE_BELOW_METERS

        val indexStarted = System.nanoTime()
        val queries = AtomicInteger()
        val tiles = index(west, south, east, north, queries)
        val indexMs = (System.nanoTime() - indexStarted) / 1_000_000

        // Project every output pixel to LV95 once.
        val w = window.width
        val h = window.height
        val xs = DoubleArray(w * h)
        val ys = DoubleArray(w * h)
        val lons = DoubleArray(w) { window.lonAt(it) }
        for (j in 0 until h) {
            val lat = window.latAt(j)
            for (i in 0 until w) {
                val p = SwissProjection.forward(lat, lons[i])
                xs[j * w + i] = p.easting
                ys[j * w + i] = p.northing
            }
        }

        // The part of each needed tile the window covers, in LV95 metres.
        class Need(val tile: Tile, var minX: Double, var minY: Double, var maxX: Double, var maxY: Double)
        val byKey = tiles.associateBy { it.e to it.n }
        val needs = HashMap<Pair<Int, Int>, Need>()
        for (k in xs.indices) {
            val key = floor(xs[k] / 1000).toInt() to floor(ys[k] / 1000).toInt()
            val tile = byKey[key] ?: continue
            val need = needs.getOrPut(key) { Need(tile, xs[k], ys[k], xs[k], ys[k]) }
            if (xs[k] < need.minX) need.minX = xs[k]
            if (xs[k] > need.maxX) need.maxX = xs[k]
            if (ys[k] < need.minY) need.minY = ys[k]
            if (ys[k] > need.maxY) need.maxY = ys[k]
        }
        var skippedDatum = 0L
        val usable = needs.values.filter { n ->
            val ok = Datums.swissGeoidFor(n.tile.verticalEpsg) != null && (if (fine) n.tile.url05 ?: n.tile.url2 else n.tile.url2) != null
            if (!ok) skippedDatum++
            ok
        }

        // Open the tiles and decode the blocks the window needs, a few requests at a time.
        val readStarted = System.nanoTime()
        val opened = usable.mapOnIo { need ->
            val t = need.tile
            val (url, res) = if (fine && t.url05 != null) t.url05 to "0.5" else t.url2!! to "2"
            val tiff = RemoteTiff(url, File(cacheDir, "tiles/${t.e}-${t.n}-${t.year}-$res"))
            (t.e to t.n) to tiff
        }.toMap()
        val levels = opened.mapValues { (_, tiff) -> tiff.levelFor(targetMeters, 1.0) }
        val blocks = ArrayList<() -> Unit>()
        for (need in usable) {
            val key = need.tile.e to need.tile.n
            val tiff = opened.getValue(key)
            val level = levels.getValue(key)
            val l = tiff.level(level)
            val c0 = floor(l.colOf(need.minX)).toInt().coerceIn(0, l.width - 1)
            val c1 = (floor(l.colOf(need.maxX)).toInt() + 1).coerceIn(0, l.width - 1)
            val r0 = floor(l.rowOf(need.maxY)).toInt().coerceIn(0, l.height - 1)
            val r1 = (floor(l.rowOf(need.minY)).toInt() + 1).coerceIn(0, l.height - 1)
            for (ty in r0 / l.tileHeight..r1 / l.tileHeight) for (tx in c0 / l.tileWidth..c1 / l.tileWidth) {
                blocks += { tiff.at(level, tx * l.tileWidth, ty * l.tileHeight) }
            }
        }
        blocks.mapOnIo { it() }
        val readMs = (System.nanoTime() - readStarted) / 1_000_000

        val values = FloatArray(w * h) { Float.NaN }
        for (k in xs.indices) {
            val key = floor(xs[k] / 1000).toInt() to floor(ys[k] / 1000).toInt()
            val tiff = opened[key] ?: continue
            values[k] = tiff.sample(levels.getValue(key), xs[k], ys[k])
        }

        // LN02 (or LHN95) -> EGM2008.
        val epsgs = usable.map { it.tile.verticalEpsg }.toSet()
        var meanOffset = 0.0
        var unconverted = 0L
        val geoidIds = LinkedHashSet<String>()
        if (epsgs.isNotEmpty()) {
            // One vertical system per window in practice (all tiles are LN02 today); a mixed window
            // would need per-tile offsets, so refuse rather than mis-convert.
            check(epsgs.size == 1) { "swissALTI3D tiles in mixed vertical systems: $epsgs" }
            val geoid = Datums.swissGeoidFor(epsgs.single())!!
            val offsets = offsets(window, geoid.file)
            var sum = 0.0
            var n = 0L
            for (k in values.indices) {
                if (values[k].isNaN()) continue
                val o = offsets[k]
                if (o.isNaN()) {
                    values[k] = Float.NaN
                    unconverted++
                } else {
                    values[k] += o
                    sum += o
                    n++
                }
            }
            if (n > 0) {
                meanOffset = sum / n
                geoidIds += geoid.registryId
                geoidIds += "egm2008"
            }
        }

        val grid = ElevationGrid(w, h, window.cellSizeMeters, values)
        val counters = LinkedHashMap<String, Long>()
        counters["indexCells"] = cellsFor(west, south, east, north).size.toLong()
        counters["indexQueries"] = queries.get().toLong()
        counters["indexMs"] = indexMs
        counters["tilesUsed"] = opened.size.toLong()
        counters["tilesSkippedDatum"] = skippedDatum
        counters["productDecimeters"] = if (fine) 5L else 20L
        counters["overviewLevelMax"] = (levels.values.maxOrNull() ?: 0).toLong()
        counters["blocksDecoded"] = opened.values.sumOf { it.tilesDecoded }.toLong()
        counters["rangeRequests"] = opened.values.sumOf { it.source.fetches }
        counters["readMs"] = readMs
        counters["datumOffsetMeanCm"] = Math.round(meanOffset * 100)
        counters["datumUnconvertedCells"] = unconverted
        counters["coveragePermille"] = (grid.values.count { !it.isNaN() } * 1000L) / grid.values.size
        counters["noDataCells"] = grid.values.count { it.isNaN() }.toLong()
        return ElevationSource.Result(grid, counters, ElevationSource.provenanceIfData(grid, listOf("swissalti3d") + geoidIds))
    }

    /**
     * Per-pixel meters to add (CHGeo2004 minus EGM2008), computed on a 16-pixel lattice aligned to
     * the window origin and interpolated: both geoids vary over kilometres.
     */
    private fun offsets(window: MercatorWindow, file: String): FloatArray {
        val chgeo = swissGeoid(file)
        val step = 16
        val lw = (window.width - 1) / step + 2
        val lh = (window.height - 1) / step + 2
        val west = window.lonAt(0)
        val east = window.lonAt((lw - 1) * step)
        val south = window.latAt((lh - 1) * step)
        val north = window.latAt(0)
        chgeo.prefetch(west, south, east, north)
        Geoids.egm2008.prefetch(west, south, east, north)
        val lattice = DoubleArray(lw * lh)
        for (b in 0 until lh) for (a in 0 until lw) {
            lattice[b * lw + a] = Datums.swissToEgm2008(window.latAt(b * step), window.lonAt(a * step), chgeo, Geoids.egm2008)
        }
        val out = FloatArray(window.width * window.height)
        for (j in 0 until window.height) {
            val b = j / step
            val fy = (j - b * step).toDouble() / step
            for (i in 0 until window.width) {
                val a = i / step
                val fx = (i - a * step).toDouble() / step
                val v00 = lattice[b * lw + a]
                val v10 = lattice[b * lw + a + 1]
                val v01 = lattice[(b + 1) * lw + a]
                val v11 = lattice[(b + 1) * lw + a + 1]
                out[j * window.width + i] = ((v00 * (1 - fx) + v10 * fx) * (1 - fy) + (v01 * (1 - fx) + v11 * fx) * fy).toFloat()
            }
        }
        return out
    }

    /** Tiles of every 0.1° index cell the box touches, newest year per tile. */
    private fun index(west: Double, south: Double, east: Double, north: Double, queries: AtomicInteger): List<Tile> {
        val perCell = cellsFor(west, south, east, north).mapOnIo { (la, lo) ->
            cellIndex.computeIfAbsent("${cacheDir.path}|$la|$lo") { lazy { loadCell(la, lo, queries) } }.value
        }
        val newest = HashMap<Pair<Int, Int>, Tile>()
        for (t in perCell.flatten()) {
            val key = t.e to t.n
            val old = newest[key]
            if (old == null || t.year > old.year) newest[key] = t
        }
        return newest.values.toList()
    }

    /** The STAC items listed for the 0.1° cell with south-west corner ([la], [lo]) in tenths of a degree, all pages. */
    private fun loadCell(la: Int, lo: Int, queries: AtomicInteger): List<Tile> {
        val cache = File(cacheDir, "index/cell-$la-$lo.json")
        if (cache.exists()) return json.decodeFromString<CellIndex>(cache.readText()).tiles
        val bbox = String.format(Locale.ROOT, "%.1f,%.1f,%.1f,%.1f", lo / 10.0, la / 10.0, (lo + 1) / 10.0, (la + 1) / 10.0)
        var url: String? = "$STAC_ITEMS?bbox=$bbox&limit=$PAGE"
        val tiles = ArrayList<Tile>()
        var page = 0
        while (url != null) {
            val tmp = File(cacheDir, "index/cell-$la-$lo-$page.${ProcessHandle.current().pid()}-${Thread.currentThread().threadId()}.page")
            val body = LidarSource.retryTnm {
                try {
                    Http.download(url!!, tmp)
                    queries.incrementAndGet()
                    json.parseToJsonElement(tmp.readText()).jsonObject
                } finally {
                    tmp.delete()
                }
            }
            tiles += parseItems(body)
            url = body["links"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["rel"]?.jsonPrimitive?.content == "next" }?.get("href")?.jsonPrimitive?.content
            page++
            check(page < 100) { "STAC index for a cell did not end after 100 pages" }
        }
        cache.parentFile.mkdirs()
        val part = Http.partFile(cache)
        part.writeText(json.encodeToString(CellIndex.serializer(), CellIndex(tiles)))
        Http.publish(part, cache)
        return tiles
    }

    companion object {
        const val STAC_ITEMS = "https://data.geo.admin.ch/api/stac/v0.9/collections/ch.swisstopo.swissalti3d/items"

        /** The collection's spatial extent (STAC `extent.spatial.bbox`): west, south, east, north. */
        val COVERAGE = doubleArrayOf(5.9503666, 45.7213375, 10.4998461, 47.8216742)

        /**
         * Coarsest output cell, in meters, for which swissALTI3D is read. Above it Copernicus (30 m)
         * already samples every output cell, and the 1 km tiles would cost thousands of requests per
         * window (a z10 tile spans ~1500 of them). 30 m keeps z12 (~26 m in Switzerland) and up.
         */
        const val MAX_USEFUL_CELL_METERS = 30.0

        /** Output cells finer than this read the 0.5 m product (z16 and up in Switzerland). */
        const val FINE_BELOW_METERS = 2.0

        /** Simultaneous requests to data.geo.admin.ch. */
        const val MAX_PARALLEL = 4

        private const val PAGE = 100

        fun useful(window: MercatorWindow): Boolean = OverviewLevels.targetMeters(window) <= MAX_USEFUL_CELL_METERS

        fun intersectsCoverage(west: Double, south: Double, east: Double, north: Double): Boolean =
            east >= COVERAGE[0] && west <= COVERAGE[2] && north >= COVERAGE[1] && south <= COVERAGE[3]

        /**
         * Tiles from one STAC items page: ids are `swissalti3d_<year>_<E km>-<N km>`, assets
         * `..._<gsd>_2056_<vertical EPSG>.tif`.
         */
        fun parseItems(page: JsonObject): List<Tile> {
            val out = ArrayList<Tile>()
            val idPattern = Regex("""^swissalti3d_(\d{4})_(\d{4})-(\d{4})$""")
            val assetPattern = Regex("""_(0\.5|2)_2056_(\d{4})\.tif$""")
            for (f in page["features"]?.jsonArray.orEmpty()) {
                val feature = f.jsonObject
                val m = idPattern.find(feature["id"]?.jsonPrimitive?.content ?: continue) ?: continue
                var url2: String? = null
                var url05: String? = null
                var vertical = 0
                for ((name, asset) in feature["assets"]?.jsonObject.orEmpty()) {
                    val a = assetPattern.find(name) ?: continue
                    val href = asset.jsonObject["href"]?.jsonPrimitive?.content ?: continue
                    if (a.groupValues[1] == "2") url2 = href else url05 = href
                    vertical = a.groupValues[2].toInt()
                }
                if (url2 == null && url05 == null) continue
                out += Tile(m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[1].toInt(), url2, url05, vertical)
            }
            return out
        }

        /** South-west corners, in tenths of a degree, of the 0.1° cells the box touches (clipped to the coverage). */
        fun cellsFor(west: Double, south: Double, east: Double, north: Double): List<Pair<Int, Int>> {
            val w = maxOf(west, COVERAGE[0])
            val e = minOf(east, COVERAGE[2])
            val s = maxOf(south, COVERAGE[1])
            val n = minOf(north, COVERAGE[3])
            if (w > e || s > n) return emptyList()
            val out = ArrayList<Pair<Int, Int>>()
            for (la in floor(s * 10).toInt()..floor(n * 10).toInt()) for (lo in floor(w * 10).toInt()..floor(e * 10).toInt()) out += la to lo
            return out
        }

        /**
         * How well a regional layer [top] blends into [fallback] in [merged], at the edge of its
         * coverage: over every pair of 4-neighbours where one cell has [top] data and the other has
         * none, the largest jump in (merged - fallback) is the step the composite adds (0 for a perfect
         * feather), and the largest |top - fallback| at those cells is the step a hard cut would have
         * made. Both in meters; null when [top] has no edge in the window.
         */
        fun seamSteps(top: ElevationGrid, fallback: ElevationGrid, merged: ElevationGrid): Pair<Double, Double>? {
            val w = top.width
            var feathered = -1.0
            var hard = -1.0
            fun pair(a: Int, b: Int) {
                val ta = top.values[a]
                val tb = top.values[b]
                if (ta.isNaN() == tb.isNaN()) return
                val inside = if (ta.isNaN()) b else a
                val outside = if (ta.isNaN()) a else b
                if (fallback.values[inside].isNaN() || fallback.values[outside].isNaN()) return
                val d = (merged.values[inside] - fallback.values[inside]) - (merged.values[outside] - fallback.values[outside])
                feathered = maxOf(feathered, kotlin.math.abs(d).toDouble())
                hard = maxOf(hard, kotlin.math.abs(top.values[inside] - fallback.values[inside]).toDouble())
            }
            for (j in 0 until top.height) for (i in 0 until w) {
                val k = j * w + i
                if (i + 1 < w) pair(k, k + 1)
                if (j + 1 < top.height) pair(k, k + w)
            }
            return if (feathered < 0) null else feathered to hard
        }

        private val cellIndex = ConcurrentHashMap<String, Lazy<List<Tile>>>()

        private val geoids = ConcurrentHashMap<String, ProjGeoid>()

        /** A CHGeo2004 grid from the PROJ CDN, opened once per process. */
        fun swissGeoid(file: String): ProjGeoid =
            geoids.computeIfAbsent(file) { ProjGeoid("https://cdn.proj.org/$it", Caches.dir("geoids/${it.removeSuffix(".tif")}")) }

        private val json = Json { ignoreUnknownKeys = true }

        /** A small pool so a cold render never has more than [MAX_PARALLEL] requests in flight to swisstopo. */
        private val ioPool = Executors.newFixedThreadPool(MAX_PARALLEL) { r -> Thread(r, "swissalti-io").apply { isDaemon = true } }

        private fun <T, R> List<T>.mapOnIo(f: (T) -> R): List<R> =
            map { ioPool.submit(Callable { f(it) }) }.map {
                try {
                    it.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
    }
}
