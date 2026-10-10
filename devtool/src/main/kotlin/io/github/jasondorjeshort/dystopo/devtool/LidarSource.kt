package io.github.jasondorjeshort.dystopo.devtool

import io.github.jasondorjeshort.dystopo.dem.Composite
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.Utm
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.floor

/**
 * USGS 3DEP 1 m lidar DEMs. Coverage is patchy and comes from separate survey projects, each
 * a set of 10x10 km UTM tiles (NAD83 / UTM, NAVD88 meters) published as Cloud-Optimized
 * GeoTIFFs with internal overviews at 2, 4, 8... m.
 *
 * Tiles are found through the USGS TNM Access API. Each project is sampled separately, then
 * projects are composited newest survey on top, feathered at coverage edges. Cells with no
 * lidar are NaN, so this source is meant to be composited over a fallback (see [ThreeDepBestSource]).
 */
class LidarSource(private val cacheDir: File = Caches.dir("3dep1m")) : FeatheredSource {
    override val id = "3dep-1m"
    override val registryIds = listOf("3dep-1m") + Geoids.REGISTRY_IDS

    @Serializable
    private data class TnmBox(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

    @Serializable
    private data class TnmItem(
        val title: String = "",
        val downloadURL: String = "",
        val publicationDate: String = "",
        val boundingBox: TnmBox? = null,
    )

    @Serializable
    private data class TnmResponse(val total: Int = 0, val items: List<TnmItem> = emptyList())

    private class Project(val name: String, val year: Int, val published: String, val tiles: List<RemoteTiff>)

    /** Per-project grids (NaN outside coverage), oldest first, plus counters and the geoid grids that converted them. */
    data class Layers(val grids: List<ElevationGrid>, val counters: Map<String, Long>, val geoidIds: List<String> = emptyList())

    /** What [index] did for one window, for the report. */
    private class IndexStats(val cells: Int, val queries: Int, val ms: Long)

    /**
     * Lidar tiles whose footprint overlaps [window]. The TNM API is asked once per 1° cell rather
     * than per window: every tile of an export (and every zoom) shares the same few queries, on disk
     * and in memory. Items carry their bounding box, so the window's own list is a cheap filter.
     */
    private fun index(window: MercatorWindow): Pair<List<TnmItem>, IndexStats> {
        val started = System.nanoTime()
        val w = window.lonAt(0)
        val e = window.lonAt(window.width - 1)
        val n = window.latAt(0)
        val s = window.latAt(window.height - 1)
        val cells = ArrayList<Pair<Int, Int>>()
        for (lat in floor(s).toInt()..floor(n).toInt()) for (lon in floor(w).toInt()..floor(e).toInt()) cells += lat to lon
        val queries = java.util.concurrent.atomic.AtomicInteger()
        val perCell = cells.mapOnIo { (lat, lon) ->
            cellIndex.computeIfAbsent(cellKey(cacheDir, lat, lon)) { lazy { loadCell(lat, lon, queries) } }.value
        }
        // A tile straddling a cell edge is listed by both cells; keep the first.
        val seen = HashSet<String>()
        val items = perCell.flatten().filter { item ->
            val b = item.boundingBox
            val overlaps = b == null || (b.maxX >= w && b.minX <= e && b.maxY >= s && b.minY <= n)
            overlaps && seen.add(item.downloadURL)
        }
        return items to IndexStats(cells.size, queries.get(), (System.nanoTime() - started) / 1_000_000)
    }

    /** Every lidar tile listed for the 1° cell with south-west corner ([lat], [lon]), all pages. */
    private fun loadCell(lat: Int, lon: Int, queries: java.util.concurrent.atomic.AtomicInteger): List<TnmItem> {
        // Keyed by a hash so the location isn't spelled out in file names.
        val key = String.format(Locale.ROOT, "%d,%d", lat, lon)
        val cache = File(cacheDir, "index/cell-${key.hashCode().toUInt()}.json")
        if (cache.exists()) return json.decodeFromString<TnmResponse>(cache.readText()).items
        val bbox = String.format(Locale.ROOT, "%d,%d,%d,%d", lon, lat, lon + 1, lat + 1)
        val dataset = URLEncoder.encode("Digital Elevation Model (DEM) 1 meter", StandardCharsets.UTF_8)
        fun page(offset: Int): TnmResponse {
            val url = "https://tnmaccess.nationalmap.gov/api/v1/products?bbox=$bbox&datasets=$dataset&prodFormats=GeoTIFF&max=$PAGE&offset=$offset"
            // Unique per thread: other processes sharing the cache may be fetching the same cell.
            val tmp = File(cacheDir, "index/cell-${key.hashCode().toUInt()}-$offset.${ProcessHandle.current().pid()}-${Thread.currentThread().threadId()}.page")
            return retryTnm {
                try {
                    Http.download(url, tmp)
                    queries.incrementAndGet()
                    json.decodeFromString<TnmResponse>(tmp.readText())
                } finally {
                    tmp.delete()
                }
            }
        }
        // The first page says how many there are; fetch the rest in parallel.
        val first = page(0)
        val rest = (PAGE until first.total step PAGE).toList().parallelStream().map { page(it) }.toList()
        val items = first.items + rest.flatMap { it.items }
        check(items.size >= first.total || rest.any { it.items.isEmpty() }) { "TNM index for a cell came back short: ${items.size} of ${first.total}" }
        // Keep only what we use; the full API records are mostly long descriptions.
        val slim = TnmResponse(first.total, items.map { TnmItem(it.title, it.downloadURL, it.publicationDate, it.boundingBox) })
        cache.parentFile.mkdirs()
        val part = Http.partFile(cache)
        part.writeText(json.encodeToString(TnmResponse.serializer(), slim))
        Http.publish(part, cache)
        return slim.items
    }

    private fun projects(items: List<TnmItem>): List<Project> {
        val projectPattern = Regex("/Projects/([^/]+)/")
        val yearPattern = Regex("_(\\d{4})(_|$)")
        val tifs = items.filter { it.downloadURL.endsWith(".tif") }
        fun projectOf(item: TnmItem) = projectPattern.find(item.downloadURL)?.groupValues?.get(1) ?: "unknown"
        // Open every tile's header at once, across projects: cold, this is one range request each.
        val opened = tifs.mapOnIo { item ->
            item.downloadURL to RemoteTiff(item.downloadURL, File(cacheDir, "${projectOf(item)}/${item.downloadURL.substringAfterLast('/').removeSuffix(".tif")}"))
        }.toMap()
        return tifs
            .groupBy { projectOf(it) }
            .map { (name, items) ->
                val tiles = items.map { opened.getValue(it.downloadURL) }
                Project(
                    name = name,
                    year = yearPattern.find(name)?.groupValues?.get(1)?.toInt() ?: 0,
                    published = items.maxOf { it.publicationDate },
                    tiles = tiles,
                )
            }
            .sortedWith(compareBy({ it.year }, { it.published }))
    }

    fun layers(window: MercatorWindow): Layers {
        // No geoid grid covers the window: its NAVD88 heights would be discarded, so don't query or download.
        if (!Geoids.covers(window)) return Layers(emptyList(), mapOf("datumUncovered" to 1L))
        // Read on this thread: the export's override is thread-local (see OverviewLevels).
        val targetMeters = OverviewLevels.targetMeters(window)
        val (items, indexStats) = index(window)
        val openStarted = System.nanoTime()
        val projects = projects(items)
        val openMs = (System.nanoTime() - openStarted) / 1_000_000
        val lons = DoubleArray(window.width) { window.lonAt(it) }
        val lats = DoubleArray(window.height) { window.latAt(it) }

        // Project every output pixel center into each UTM zone in use, once.
        val zones = projects.flatMap { p -> p.tiles.map { zoneOf(it) } }.toSet()
        val projected = zones.associateWith { (zone, north) ->
            val xs = DoubleArray(window.width * window.height)
            val ys = DoubleArray(window.width * window.height)
            for (j in 0 until window.height) for (i in 0 until window.width) {
                val p = Utm.forward(lats[j], lons[i], zone, north)
                xs[j * window.width + i] = p.easting
                ys[j * window.width + i] = p.northing
            }
            xs to ys
        }

        // Find which part of each tile the window needs and read it, all tiles in parallel: cold,
        // each tile costs a few range requests, and one at a time that dominated low-zoom renders.
        val readStarted = System.nanoTime()
        val levels = projects.flatMap { it.tiles }.mapOnIo { tile ->
            val (xs, ys) = projected.getValue(zoneOf(tile))
            val level = tile.levelFor(targetMeters, 1.0)
            val e = tile.tiff.extent
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            for (k in xs.indices) {
                val x = xs[k]
                val y = ys[k]
                if (x < e[0] || x >= e[2] || y <= e[1] || y > e[3]) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            if (minX > maxX) return@mapOnIo tile to -1 // tile listed by the API but outside the window
            tile.prefetch(level, minX, minY, maxX, maxY)
            tile to level
        }.toMap()
        val readMs = (System.nanoTime() - readStarted) / 1_000_000

        val grids = ArrayList<ElevationGrid>()
        var tilesUsed = 0L
        for (project in projects) {
            val values = FloatArray(window.width * window.height) { Float.NaN }
            for (tile in project.tiles) {
                val level = levels.getValue(tile)
                if (level < 0) continue
                val (xs, ys) = projected.getValue(zoneOf(tile))
                tilesUsed++
                for (k in xs.indices) {
                    if (!values[k].isNaN() || !tile.contains(xs[k], ys[k])) continue
                    values[k] = tile.sample(level, xs[k], ys[k])
                }
            }
            grids += ElevationGrid(window.width, window.height, window.cellSizeMeters, values)
        }

        // USGS lidar DEMs are NAVD88; convert to the internal EGM2008 datum.
        var unconverted = 0L
        val offsets = if (grids.isEmpty()) null else Geoids.navd88ToEgm2008(window)
        if (offsets != null) for (g in grids) unconverted += Geoids.apply(g.values, offsets).second

        val counters = LinkedHashMap<String, Long>()
        counters["datumUnconvertedCells"] = unconverted
        counters["lidarProjects"] = projects.size.toLong()
        counters["lidarTilesUsed"] = tilesUsed
        counters["tilesDecoded"] = projects.sumOf { p -> p.tiles.sumOf { it.tilesDecoded } }.toLong()
        counters["rangeRequests"] = projects.sumOf { p -> p.tiles.sumOf { it.source.fetches } }
        counters["indexCells"] = indexStats.cells.toLong()
        counters["indexQueries"] = indexStats.queries.toLong()
        counters["indexMs"] = indexStats.ms
        counters["openMs"] = openMs
        counters["readMs"] = readMs
        // Project names can identify a place, so report coverage per project index only.
        grids.forEachIndexed { i, g -> counters["project${i}CoveragePermille"] = (Composite.coverage(g) * 1000).toLong() }
        return Layers(grids, counters, offsets?.registryIds.orEmpty())
    }

    private fun zoneOf(tile: RemoteTiff): Pair<Int, Boolean> {
        val epsg = tile.tiff.epsg ?: error("lidar tile has no EPSG code")
        return Utm.zoneFromEpsg(epsg) ?: error("lidar tile EPSG:$epsg is not a supported UTM zone")
    }

    override fun fetch(window: MercatorWindow, featherCellSizeMeters: Double): ElevationSource.Result {
        val layers = layers(window)
        val merged = mergeNewestOnTop(layers.grids, featherCellSizeMeters) ?: ElevationGrid(
            window.width, window.height, window.cellSizeMeters, FloatArray(window.width * window.height) { Float.NaN },
        )
        val counters = layers.counters + ("coveragePermille" to (Composite.coverage(merged) * 1000).toLong())
        return ElevationSource.Result(merged, counters, ElevationSource.provenanceIfData(merged, listOf("3dep-1m") + layers.geoidIds))
    }

    companion object {
        /** Width of the blend band at coverage edges. */
        const val FEATHER_METERS = 60.0

        /**
         * Coarsest output cell, in meters, for which the composites ([ThreeDepBestSource], so `best`)
         * read lidar. Above it the 10 m 3DEP DEM already has at least two samples per output cell, so
         * 1 m lidar (read from a 16 m or coarser overview by then) adds no visible detail but costs
         * the TNM index queries and hundreds of tile reads. 20 m falls between z12 (~26 m at
         * Rainier's latitude, skipped) and z13 (~13 m, read). The `3dep-1m` source itself always
         * reads lidar, so it stays useful for diagnosis at any zoom.
         */
        const val MAX_USEFUL_CELL_METERS = 20.0

        /**
         * Whether lidar can contribute to [window]. Decided on [OverviewLevels.targetMeters], not the
         * window's own cell size, so every tile of an export makes the same choice for a zoom level
         * (no lidar/no-lidar seam at some latitude in a tall export).
         */
        fun useful(window: MercatorWindow): Boolean = OverviewLevels.targetMeters(window) <= MAX_USEFUL_CELL_METERS

        /** TNM API page size. */
        private const val PAGE = 200

        /** Per-process TNM index, one entry per (cache dir, 1° cell); lazy so each cell is queried once. */
        private val cellIndex = java.util.concurrent.ConcurrentHashMap<String, Lazy<List<TnmItem>>>()

        private fun cellKey(cacheDir: File, lat: Int, lon: Int) = "${cacheDir.path}|$lat|$lon"

        private val json = Json { ignoreUnknownKeys = true }

        /** TNM index retries: 5 tries, 1 s doubling (15 s of waiting in all), enough to ride out a brief API outage. */
        const val TNM_ATTEMPTS = 5
        const val TNM_INITIAL_DELAY_MS = 1000L

        /**
         * Failures worth retrying on the TNM API: dropped or timed-out connections, server errors and
         * rate limiting (5xx, 429), and a truncated or garbled JSON body (the API sometimes returns an
         * HTML error page with status 200). A 4xx means the query itself is wrong; retrying won't help.
         */
        fun isTransientTnmFailure(e: Exception): Boolean = when (e) {
            is java.io.IOException -> true
            is HttpStatusException -> e.status >= 500 || e.status == 429
            is kotlinx.serialization.SerializationException -> true
            else -> false
        }

        /** Runs one TNM API request with retry and backoff (see [isTransientTnmFailure]). */
        fun <T> retryTnm(initialDelayMs: Long = TNM_INITIAL_DELAY_MS, block: () -> T): T =
            Http.retry(TNM_ATTEMPTS, initialDelayMs, ::isTransientTnmFailure, block)

        /**
         * Threads for lidar I/O fan-out (index cells, tile headers, tile reads). Not the common
         * fork-join pool: these tasks block on HTTP and start parallel streams of their own (tile
         * prefetch, index pages), and blocking nested inside that pool runs it out of
         * compensation threads. Tasks here never wait on other tasks here, so it can't deadlock.
         */
        private val ioPool = java.util.concurrent.Executors.newFixedThreadPool(16) { r ->
            Thread(r, "lidar-io").apply { isDaemon = true }
        }

        /** [map] with each element run on [ioPool]; rethrows the first failure. */
        private fun <T, R> List<T>.mapOnIo(f: (T) -> R): List<R> =
            map { ioPool.submit(java.util.concurrent.Callable { f(it) }) }.map {
                try {
                    it.get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw e.cause ?: e
                }
            }

        /** Blend band in cells for grids whose cells are [cellSizeMeters] on the ground. */
        fun featherCells(cellSizeMeters: Double) = FEATHER_METERS / cellSizeMeters

        /** Composites [grids] (oldest first) so newer surveys win, feathered at their edges. */
        fun mergeNewestOnTop(grids: List<ElevationGrid>, cellSizeMeters: Double): ElevationGrid? =
            grids.reduceOrNull { below, above -> Composite.over(above, below, featherCells(cellSizeMeters)) }
    }
}

/** Best available USGS elevation: 1 m lidar where it exists, feathered into the 10 m DEM elsewhere. */
class ThreeDepBestSource : FeatheredSource {
    override val id = "3dep-best"
    override val registryIds = listOf("3dep", "3dep-1m") + Geoids.REGISTRY_IDS

    override fun fetch(window: MercatorWindow, featherCellSizeMeters: Double): ElevationSource.Result {
        val base = ThreeDepSource().fetch(window)
        // Coarse output: lidar can't add detail the 10 m DEM lacks, so don't query or read it at all.
        val lidar = if (LidarSource.useful(window)) LidarSource().layers(window)
        else LidarSource.Layers(emptyList(), mapOf("skippedCoarse" to 1L))
        val merged = LidarSource.mergeNewestOnTop(listOf(base.grid) + lidar.grids, featherCellSizeMeters)!!
        val counters = LinkedHashMap<String, Long>()
        base.counters.forEach { (k, v) -> counters["base.$k"] = v }
        lidar.counters.forEach { (k, v) -> counters["lidar.$k"] = v }
        counters["noDataCells"] = merged.values.count { it.isNaN() }.toLong()
        // Provenance: lidar if any project has data; the 10 m base if it shows through a gap in lidar cover.
        val lidarIds = if (lidar.grids.any { g -> g.values.any { !it.isNaN() } }) listOf("3dep-1m") + lidar.geoidIds else emptyList()
        val baseShows = base.grid.values.indices.any { k -> lidar.grids.all { it.values[k].isNaN() } }
        return ElevationSource.Result(
            merged, counters, ElevationSource.mergeProvenance(if (baseShows) base.provenance else emptyList(), lidarIds),
        )
    }
}

/**
 * Best available elevation anywhere: Copernicus 30 m globally, with US 3DEP (10 m, then 1 m lidar)
 * laid over it where available. Everything is in EGM2008. The 30 m -> 10 m transition gets a
 * wider blend band than lidar -> 10 m, scaled to the coarser source's resolution.
 */
class BestSource : FeatheredSource {
    companion object {
        const val GLOBAL_FEATHER_METERS = 300.0
    }

    override val id = "best"
    override val registryIds = listOf("copernicus", "3dep", "3dep-1m") + Geoids.REGISTRY_IDS

    override fun fetch(window: MercatorWindow, featherCellSizeMeters: Double): ElevationSource.Result {
        val global = CopernicusSource().fetch(window)
        val us = ThreeDepBestSource().fetch(window, featherCellSizeMeters)
        val merged = Composite.over(us.grid, global.grid, GLOBAL_FEATHER_METERS / featherCellSizeMeters)
        val counters = LinkedHashMap<String, Long>()
        global.counters.forEach { (k, v) -> counters["copernicus.$k"] = v }
        us.counters.forEach { (k, v) -> counters["3dep.$k"] = v }
        counters["usCoveragePermille"] = (Composite.coverage(us.grid) * 1000).toLong()
        counters["noDataCells"] = merged.values.count { it.isNaN() }.toLong()
        // Each layer counts only if it shows: the US layers have data (they're NaN outside the US), and
        // Copernicus is visible wherever the US layers have holes (Composite.over leaves full US cover untouched).
        val globalShows = us.grid.values.any { it.isNaN() }
        return ElevationSource.Result(
            merged, counters,
            ElevationSource.mergeProvenance(if (globalShows) global.provenance else emptyList(), us.provenance),
        )
    }
}
