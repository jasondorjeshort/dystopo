package io.github.jasondorjeshort.dystopo.sources.elevation

import io.github.jasondorjeshort.dystopo.dem.Composite
import io.github.jasondorjeshort.dystopo.dem.ElevationGrid
import io.github.jasondorjeshort.dystopo.geo.MercatorWindow
import io.github.jasondorjeshort.dystopo.geo.Utm
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
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
class LidarSource(private val cacheDir: String = SourceIo.dir("3dep1m")) : FeatheredSource {
    override val id = "3dep-1m"
    override val registryIds = listOf("3dep-1m") + Geoids.REGISTRY_IDS

    internal data class TnmBox(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

    internal data class TnmItem(
        val title: String = "",
        val downloadURL: String = "",
        val publicationDate: String = "",
        val boundingBox: TnmBox? = null,
    )

    internal data class TnmResponse(val total: Int = 0, val items: List<TnmItem> = emptyList())

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
        val started = nanoTime()
        val w = window.lonAt(0)
        val e = window.lonAt(window.width - 1)
        val n = window.latAt(0)
        val s = window.latAt(window.height - 1)
        val cells = ArrayList<Pair<Int, Int>>()
        for (lat in floor(s).toInt()..floor(n).toInt()) for (lon in floor(w).toInt()..floor(e).toInt()) cells += lat to lon
        val queries = Counter()
        val perCell = cells.mapOnIo { (lat, lon) ->
            cellIndex.getOrPut(cellKey(cacheDir, lat, lon)) { lazy { loadCell(lat, lon, queries) } }.value
        }
        // A tile straddling a cell edge is listed by both cells; keep the first.
        val seen = HashSet<String>()
        val items = perCell.flatten().filter { item ->
            val b = item.boundingBox
            val overlaps = b == null || (b.maxX >= w && b.minX <= e && b.maxY >= s && b.minY <= n)
            overlaps && seen.add(item.downloadURL)
        }
        return items to IndexStats(cells.size, queries.get().toInt(), msSince(started))
    }

    /** Every lidar tile listed for the 1° cell with south-west corner ([lat], [lon]), all pages. */
    private fun loadCell(lat: Int, lon: Int, queries: Counter): List<TnmItem> {
        // Keyed by a hash so the location isn't spelled out in file names.
        val key = "$lat,$lon"
        val cache = "$cacheDir/index/cell-${key.hashCode().toUInt()}.json"
        SourceFiles.read(cache)?.let { return parseResponse(it.decodeToString()).items }
        val bbox = "$lon,$lat,${lon + 1},${lat + 1}"
        fun page(offset: Int): TnmResponse {
            val url = "https://tnmaccess.nationalmap.gov/api/v1/products?bbox=$bbox&datasets=$DATASET&prodFormats=GeoTIFF&max=$PAGE&offset=$offset"
            return retryTnm {
                val body = SourceIo.download(url)
                queries.increment()
                parseResponse(body.decodeToString())
            }
        }
        // The first page says how many there are; fetch the rest in parallel.
        val first = page(0)
        val rest = (PAGE until first.total step PAGE).toList().mapParallel { page(it) }
        val items = first.items + rest.flatMap { it.items }
        check(items.size >= first.total || rest.any { it.items.isEmpty() }) { "TNM index for a cell came back short: ${items.size} of ${first.total}" }
        // Keep only what we use; the full API records are mostly long descriptions.
        val slim = TnmResponse(first.total, items.map { TnmItem(it.title, it.downloadURL, it.publicationDate, it.boundingBox) })
        SourceFiles.write(cache, encodeResponse(slim).encodeToByteArray())
        return slim.items
    }

    private fun projects(items: List<TnmItem>): List<Project> {
        val projectPattern = Regex("/Projects/([^/]+)/")
        val yearPattern = Regex("_(\\d{4})(_|$)")
        val tifs = items.filter { it.downloadURL.endsWith(".tif") }
        fun projectOf(item: TnmItem) = projectPattern.find(item.downloadURL)?.groupValues?.get(1) ?: "unknown"
        // Open every tile's header at once, across projects: cold, this is one range request each.
        val opened = tifs.mapOnIo { item ->
            item.downloadURL to RemoteTiff.open(item.downloadURL, "$cacheDir/${projectOf(item)}/${item.downloadURL.substringAfterLast('/').removeSuffix(".tif")}")
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
        val openStarted = nanoTime()
        val projects = projects(items)
        val openMs = (nanoTime() - openStarted) / 1_000_000
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
        val readStarted = nanoTime()
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
        val readMs = (nanoTime() - readStarted) / 1_000_000

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
        private val cellIndex = SyncMap<String, Lazy<List<TnmItem>>>()

        private fun cellKey(cacheDir: String, lat: Int, lon: Int) = "$cacheDir|$lat|$lon"

        /** `Digital Elevation Model (DEM) 1 meter`, form-encoded. */
        private const val DATASET = "Digital+Elevation+Model+%28DEM%29+1+meter"

        private fun num(v: Any?): Double = (v as Number).toDouble()

        /**
         * A TNM products page, or the slim copy this source caches (the same shape: `total`, `items` with `title`,
         * `downloadURL`, `publicationDate`, `boundingBox`; missing fields take their defaults, unknown ones are
         * ignored). Anything that is not such a JSON object is an [IllegalArgumentException] (a garbled body,
         * an HTML error page), which [retryTnm] retries.
         */
        internal fun parseResponse(text: String): TnmResponse = try {
            val o = MiniJson.parseObject(text)
            val items = (o["items"] as? List<*>).orEmpty().map { raw ->
                val m = raw as Map<*, *>
                val b = m["boundingBox"] as? Map<*, *>
                TnmItem(
                    title = m["title"] as? String ?: "",
                    downloadURL = m["downloadURL"] as? String ?: "",
                    publicationDate = m["publicationDate"] as? String ?: "",
                    boundingBox = b?.let { TnmBox(num(it["minX"]), num(it["minY"]), num(it["maxX"]), num(it["maxY"])) },
                )
            }
            TnmResponse((o["total"] as? Number)?.toInt() ?: 0, items)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("TNM response does not parse: ${e.message}", e)
        }

        /** The slim cache file, in the same JSON the source has always written (fields at their defaults left out). */
        internal fun encodeResponse(r: TnmResponse): String = MiniJson.encode(
            linkedMapOf(
                "total" to r.total,
                "items" to r.items.map { i ->
                    LinkedHashMap<String, Any?>().apply {
                        if (i.title.isNotEmpty()) put("title", i.title)
                        if (i.downloadURL.isNotEmpty()) put("downloadURL", i.downloadURL)
                        if (i.publicationDate.isNotEmpty()) put("publicationDate", i.publicationDate)
                        i.boundingBox?.let { b -> put("boundingBox", linkedMapOf("minX" to b.minX, "minY" to b.minY, "maxX" to b.maxX, "maxY" to b.maxY)) }
                    }
                },
            ),
        )

        /** TNM index retries: 5 tries, 1 s doubling (15 s of waiting in all), enough to ride out a brief API outage. */
        const val TNM_ATTEMPTS = 5
        const val TNM_INITIAL_DELAY_MS = 1000L

        /**
         * Failures worth retrying on the TNM API: dropped or timed-out connections, server errors and
         * rate limiting (5xx, 429), and a truncated or garbled JSON body (the API sometimes returns an
         * HTML error page with status 200). A 4xx means the query itself is wrong; retrying won't help.
         */
        fun isTransientTnmFailure(e: Exception): Boolean = when {
            isIoException(e) -> true
            e is HttpStatusException -> e.status >= 500 || e.status == 429
            e is IllegalArgumentException -> true
            else -> false
        }

        /** Runs one TNM API request with retry and backoff (see [isTransientTnmFailure]). */
        fun <T> retryTnm(initialDelayMs: Long = TNM_INITIAL_DELAY_MS, block: () -> T): T =
            SourceIo.retry(TNM_ATTEMPTS, initialDelayMs, ::isTransientTnmFailure, block)

        /**
         * Threads for lidar I/O fan-out (index cells, tile headers, tile reads). Not the common
         * fork-join pool: these tasks block on HTTP and start parallel streams of their own (tile
         * prefetch, index pages), and blocking nested inside that pool runs it out of
         * compensation threads. Tasks here never wait on other tasks here, so it can't deadlock.
         */
        private val ioPool by lazy { IoPool("lidar-io", 16) }

        /** [map] with each element run on [ioPool]; rethrows the first failure. */
        private fun <T, R> List<T>.mapOnIo(f: (T) -> R): List<R> = ioPool.map(this, f)

        /** [map] in parallel on the platform's common pool (never on [ioPool], whose tasks call it). */
        private fun <T, R> List<T>.mapParallel(f: (T) -> R): List<R> {
            val out = arrayOfNulls<Any?>(size)
            parallelForEach(indices.toList()) { k -> out[k] = f(this[k]) }
            @Suppress("UNCHECKED_CAST")
            return out.toList() as List<R>
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
 * Best available elevation anywhere: Copernicus 30 m globally, with the national bare-earth models
 * laid over it where available: swissALTI3D, BEV, Kartverket, then US 3DEP (10 m, then 1 m lidar),
 * then NRCan HRDEM and LINZ. HRDEM goes over 3DEP because the 10 m 3DEP base reaches well into
 * Canada (half of the Garibaldi area), where the Canadian lidar is the better model. Everything is in EGM2008. The 30 m -> finer transitions get a wider blend
 * band than lidar -> 10 m, scaled to the coarser source's resolution.
 */
class BestSource : FeatheredSource {
    companion object {
        const val GLOBAL_FEATHER_METERS = 300.0
    }

    override val id = "best"
    override val registryIds = listOf(
        "copernicus", "3dep", "3dep-1m", "swissalti3d", "kartverket-dtm", "bev-als-dtm", "hrdem", "linz-dem",
        "chgeo2004", "href2018b", "bev-geoid", "cgg2013a", "nzgeoid2016",
    ) + Geoids.REGISTRY_IDS

    override fun fetch(window: MercatorWindow, featherCellSizeMeters: Double): ElevationSource.Result {
        // Regional layers barely overlap (Switzerland, Austria, Norway, US, Canada, New Zealand), so each feathers
        // into what is below it; where Swiss and Austrian data both reach past the border, Austria's goes on top,
        // and where 3DEP's 10 m base reaches into Canada, HRDEM lidar goes on top (T69).
        val swiss = SwissAltiSource().fetch(window)
        val austria = AustriaSource().fetch(window)
        val norway = NorwaySource().fetch(window)
        val us = ThreeDepBestSource().fetch(window, featherCellSizeMeters)
        val canada = CanadaSource().fetch(window)
        val newZealand = NewZealandSource().fetch(window)
        // T80: Copernicus is read only when it can show. A regional layer with no hole replaces everything below it
        // (Composite.over leaves full cover untouched), so when one does and each layer above it is either empty or
        // also whole, the result cannot depend on Copernicus: skip its reads (half the bytes of a US view on a phone).
        val stack = listOf(swiss, austria, norway, us, canada, newZealand).map { it.grid.values }
        val hidden = stack.indices.any { i ->
            stack[i].none { it.isNaN() } && (i + 1 until stack.size).all { j -> stack[j].all { it.isNaN() } || stack[j].none { it.isNaN() } }
        }
        val global = if (hidden) NationalDem.nanResult(window, mapOf("skippedCovered" to 1L)) else CopernicusSource().fetch(window)
        val feather = GLOBAL_FEATHER_METERS / featherCellSizeMeters
        val withSwiss = Composite.over(swiss.grid, global.grid, feather)
        val withAustria = Composite.over(austria.grid, withSwiss, feather)
        val withNorway = Composite.over(norway.grid, withAustria, feather)
        val withUs = Composite.over(us.grid, withNorway, feather)
        val withCanada = Composite.over(canada.grid, withUs, feather)
        val merged = Composite.over(newZealand.grid, withCanada, feather)
        val counters = LinkedHashMap<String, Long>()
        global.counters.forEach { (k, v) -> counters["copernicus.$k"] = v }
        swiss.counters.forEach { (k, v) -> counters["swissalti3d.$k"] = v }
        austria.counters.forEach { (k, v) -> counters["bev.$k"] = v }
        norway.counters.forEach { (k, v) -> counters["kartverket.$k"] = v }
        canada.counters.forEach { (k, v) -> counters["hrdem.$k"] = v }
        newZealand.counters.forEach { (k, v) -> counters["linz.$k"] = v }
        us.counters.forEach { (k, v) -> counters["3dep.$k"] = v }
        counters["usCoveragePermille"] = (Composite.coverage(us.grid) * 1000).toLong()
        counters["swissCoveragePermille"] = (Composite.coverage(swiss.grid) * 1000).toLong()
        SwissAltiSource.seamSteps(swiss.grid, global.grid, withSwiss)?.let { (feathered, hard) ->
            counters["swissSeamStepCm"] = roundL(feathered * 100)
            counters["swissSeamHardCutStepCm"] = roundL(hard * 100)
        }
        counters["austriaCoveragePermille"] = (Composite.coverage(austria.grid) * 1000).toLong()
        SwissAltiSource.seamSteps(austria.grid, withSwiss, withAustria)?.let { (feathered, hard) ->
            counters["austriaSeamStepCm"] = roundL(feathered * 100)
            counters["austriaSeamHardCutStepCm"] = roundL(hard * 100)
        }
        counters["norwayCoveragePermille"] = (Composite.coverage(norway.grid) * 1000).toLong()
        SwissAltiSource.seamSteps(norway.grid, withAustria, withNorway)?.let { (feathered, hard) ->
            counters["norwaySeamStepCm"] = roundL(feathered * 100)
            counters["norwaySeamHardCutStepCm"] = roundL(hard * 100)
        }
        counters["canadaCoveragePermille"] = (Composite.coverage(canada.grid) * 1000).toLong()
        SwissAltiSource.seamSteps(canada.grid, withUs, withCanada)?.let { (feathered, hard) ->
            counters["canadaSeamStepCm"] = roundL(feathered * 100)
            counters["canadaSeamHardCutStepCm"] = roundL(hard * 100)
        }
        counters["newZealandCoveragePermille"] = (Composite.coverage(newZealand.grid) * 1000).toLong()
        SwissAltiSource.seamSteps(newZealand.grid, withCanada, merged)?.let { (feathered, hard) ->
            counters["newZealandSeamStepCm"] = roundL(feathered * 100)
            counters["newZealandSeamHardCutStepCm"] = roundL(hard * 100)
        }
        counters["noDataCells"] = merged.values.count { it.isNaN() }.toLong()
        // Each layer counts only if it shows: the regional layers have data (they're NaN elsewhere), and
        // Copernicus is visible wherever all of them have holes (Composite.over leaves full cover untouched).
        val regional = listOf(us.grid, swiss.grid, austria.grid, norway.grid, canada.grid, newZealand.grid)
        val globalShows = us.grid.values.indices.any { k -> regional.all { it.values[k].isNaN() } }
        // 3DEP is credited only where HRDEM doesn't cover it (its 10 m base reaches into Canada).
        val usShows = us.grid.values.indices.any { k -> !us.grid.values[k].isNaN() && canada.grid.values[k].isNaN() }
        return ElevationSource.Result(
            merged, counters,
            ElevationSource.mergeProvenance(
                if (globalShows) global.provenance else emptyList(),
                swiss.provenance, austria.provenance, norway.provenance, if (usShows) us.provenance else emptyList(), canada.provenance, newZealand.provenance,
            ),
        )
    }
}
