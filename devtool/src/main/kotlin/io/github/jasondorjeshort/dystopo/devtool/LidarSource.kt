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

/**
 * USGS 3DEP 1 m lidar DEMs. Coverage is patchy and comes from separate survey projects, each
 * a set of 10x10 km UTM tiles (NAD83 / UTM, NAVD88 meters) published as Cloud-Optimized
 * GeoTIFFs with internal overviews at 2, 4, 8... m.
 *
 * Tiles are found through the USGS TNM Access API. Each project is sampled separately, then
 * projects are composited newest survey on top, feathered at coverage edges. Cells with no
 * lidar are NaN, so this source is meant to be composited over a fallback (see [ThreeDepBestSource]).
 */
class LidarSource(private val cacheDir: File = Caches.dir("3dep1m")) : ElevationSource {
    override val id = "3dep-1m"
    override val attribution = "Elevation: USGS 3D Elevation Program (3DEP) 1 m lidar DEM, public domain."

    @Serializable
    private data class TnmItem(val title: String = "", val downloadURL: String = "", val publicationDate: String = "")

    @Serializable
    private data class TnmResponse(val total: Int = 0, val items: List<TnmItem> = emptyList())

    private class Project(val name: String, val year: Int, val published: String, val tiles: List<RemoteTiff>)

    /** Per-project grids (NaN outside coverage), oldest first, plus counters. */
    data class Layers(val grids: List<ElevationGrid>, val counters: Map<String, Long>)

    private val json = Json { ignoreUnknownKeys = true }

    private fun index(window: MercatorWindow): List<TnmItem> {
        val w = window.lonAt(0)
        val e = window.lonAt(window.width - 1)
        val n = window.latAt(0)
        val s = window.latAt(window.height - 1)
        val bbox = String.format(Locale.ROOT, "%.5f,%.5f,%.5f,%.5f", w, s, e, n)
        // The cache file is keyed by a hash so the bbox itself isn't spelled out in file names.
        val cache = File(cacheDir, "index/${bbox.hashCode().toUInt()}.json")
        val text = if (cache.exists()) cache.readText() else {
            val dataset = URLEncoder.encode("Digital Elevation Model (DEM) 1 meter", StandardCharsets.UTF_8)
            val url = "https://tnmaccess.nationalmap.gov/api/v1/products?bbox=$bbox&datasets=$dataset&prodFormats=GeoTIFF&max=200"
            Http.download(url, cache)
            cache.readText()
        }
        val response = json.decodeFromString<TnmResponse>(text)
        check(response.items.size >= response.total) { "TNM returned ${response.items.size} of ${response.total} items; add paging" }
        return response.items
    }

    private fun projects(window: MercatorWindow): List<Project> {
        val projectPattern = Regex("/Projects/([^/]+)/")
        val yearPattern = Regex("_(\\d{4})(_|$)")
        return index(window)
            .filter { it.downloadURL.endsWith(".tif") }
            .groupBy { projectPattern.find(it.downloadURL)?.groupValues?.get(1) ?: "unknown" }
            .map { (name, items) ->
                val tiles = items.parallelStream().map { item ->
                    RemoteTiff(item.downloadURL, File(cacheDir, "$name/${item.downloadURL.substringAfterLast('/').removeSuffix(".tif")}"))
                }.toList()
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
        val projects = projects(window)
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

        val grids = ArrayList<ElevationGrid>()
        var tilesUsed = 0L
        for (project in projects) {
            val values = FloatArray(window.width * window.height) { Float.NaN }
            for (tile in project.tiles) {
                val (xs, ys) = projected.getValue(zoneOf(tile))
                val level = tile.levelFor(window.cellSizeMeters, 1.0)
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
                if (minX > maxX) continue // tile listed by the API but outside the window
                tilesUsed++
                tile.prefetch(level, minX, minY, maxX, maxY)
                for (k in xs.indices) {
                    if (!values[k].isNaN() || !tile.contains(xs[k], ys[k])) continue
                    values[k] = tile.sample(level, xs[k], ys[k])
                }
            }
            grids += ElevationGrid(window.width, window.height, window.cellSizeMeters, values)
        }

        // USGS lidar DEMs are NAVD88; convert to the internal EGM2008 datum.
        var unconverted = 0L
        if (grids.isNotEmpty()) {
            val offsets = Geoids.navd88ToEgm2008(window)
            for (g in grids) unconverted += Geoids.apply(g.values, offsets).second
        }

        val counters = LinkedHashMap<String, Long>()
        counters["datumUnconvertedCells"] = unconverted
        counters["lidarProjects"] = projects.size.toLong()
        counters["lidarTilesUsed"] = tilesUsed
        counters["tilesDecoded"] = projects.sumOf { p -> p.tiles.sumOf { it.tilesDecoded } }.toLong()
        counters["rangeRequests"] = projects.sumOf { p -> p.tiles.sumOf { it.source.fetches } }
        // Project names can identify a place, so report coverage per project index only.
        grids.forEachIndexed { i, g -> counters["project${i}CoveragePermille"] = (Composite.coverage(g) * 1000).toLong() }
        return Layers(grids, counters)
    }

    private fun zoneOf(tile: RemoteTiff): Pair<Int, Boolean> {
        val epsg = tile.tiff.epsg ?: error("lidar tile has no EPSG code")
        return Utm.zoneFromEpsg(epsg) ?: error("lidar tile EPSG:$epsg is not a supported UTM zone")
    }

    override fun fetch(window: MercatorWindow): ElevationSource.Result {
        val layers = layers(window)
        val merged = mergeNewestOnTop(layers.grids, window) ?: ElevationGrid(
            window.width, window.height, window.cellSizeMeters, FloatArray(window.width * window.height) { Float.NaN },
        )
        val counters = layers.counters + ("coveragePermille" to (Composite.coverage(merged) * 1000).toLong())
        return ElevationSource.Result(merged, counters)
    }

    companion object {
        /** Width of the blend band at coverage edges. */
        const val FEATHER_METERS = 60.0

        fun featherCells(window: MercatorWindow) = FEATHER_METERS / window.cellSizeMeters

        /** Composites [grids] (oldest first) so newer surveys win, feathered at their edges. */
        fun mergeNewestOnTop(grids: List<ElevationGrid>, window: MercatorWindow): ElevationGrid? =
            grids.reduceOrNull { below, above -> Composite.over(above, below, featherCells(window)) }
    }
}

/** Best available USGS elevation: 1 m lidar where it exists, feathered into the 10 m DEM elsewhere. */
class ThreeDepBestSource : ElevationSource {
    override val id = "3dep-best"
    override val attribution = "Elevation: USGS 3D Elevation Program (3DEP) 1 m lidar and 1/3 arc-second DEMs, public domain. " +
        Geoids.ATTRIBUTION

    override fun fetch(window: MercatorWindow): ElevationSource.Result {
        val base = ThreeDepSource().fetch(window)
        val lidar = LidarSource().layers(window)
        val merged = LidarSource.mergeNewestOnTop(listOf(base.grid) + lidar.grids, window)!!
        val counters = LinkedHashMap<String, Long>()
        base.counters.forEach { (k, v) -> counters["base.$k"] = v }
        lidar.counters.forEach { (k, v) -> counters["lidar.$k"] = v }
        counters["noDataCells"] = merged.values.count { it.isNaN() }.toLong()
        return ElevationSource.Result(merged, counters)
    }
}

/**
 * Best available elevation anywhere: Copernicus 30 m globally, with US 3DEP (10 m, then 1 m lidar)
 * laid over it where available. Everything is in EGM2008. The 30 m -> 10 m transition gets a
 * wider blend band than lidar -> 10 m, scaled to the coarser source's resolution.
 */
class BestSource : ElevationSource {
    companion object {
        const val GLOBAL_FEATHER_METERS = 300.0
    }

    override val id = "best"
    override val attribution = CopernicusSource().attribution + " " + ThreeDepBestSource().attribution

    override fun fetch(window: MercatorWindow): ElevationSource.Result {
        val global = CopernicusSource().fetch(window)
        val us = ThreeDepBestSource().fetch(window)
        val merged = Composite.over(us.grid, global.grid, GLOBAL_FEATHER_METERS / window.cellSizeMeters)
        val counters = LinkedHashMap<String, Long>()
        global.counters.forEach { (k, v) -> counters["copernicus.$k"] = v }
        us.counters.forEach { (k, v) -> counters["3dep.$k"] = v }
        counters["usCoveragePermille"] = (Composite.coverage(us.grid) * 1000).toLong()
        counters["noDataCells"] = merged.values.count { it.isNaN() }.toLong()
        return ElevationSource.Result(merged, counters)
    }
}
