package io.github.jasondorjeshort.dystopo.app.online

import io.github.jasondorjeshort.dystopo.geo.WebMercator
import io.github.jasondorjeshort.dystopo.packs.OsmPackMetadata
import io.github.jasondorjeshort.dystopo.packs.OsmPackWriter
import io.github.jasondorjeshort.dystopo.packs.PackBounds
import io.github.jasondorjeshort.dystopo.packs.PackNames
import io.github.jasondorjeshort.dystopo.packs.PackRole
import io.github.jasondorjeshort.dystopo.pmtiles.ByteSink
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesWriter
import io.github.jasondorjeshort.dystopo.pmtiles.TileType
import io.github.jasondorjeshort.dystopo.sources.SourceRegistry
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.math.floor

/**
 * T80: "Download the map around me" with no server. The phone makes the region pack itself: DEM tiles from
 * [DEM_MIN_ZOOM] to [DirectSources.maxZoom] (deeper ones are synthesized from the pack, as for any pack) rendered by
 * [DirectSources] from `best`, and OpenMapTiles vector tiles from OpenFreeMap up to z14, both read through
 * [fetcher] so tiles already on screen come from the online cache. The result is `<name>-dem.pmtiles` and
 * `<name>-outdoors.pmtiles` (OpenMapTiles profile, said so in the metadata, as server-built OpenFreeMap packs)
 * with the provenance and attribution of what went in; [RegionDownload] installs them like imported files, so
 * the area is pinned (packs are never evicted) and works offline. This is the box case of an area of
 * interest (design principle 4) until T75's manifests land.
 */
class LocalPackBuild(
    private val direct: DirectSources,
    private val fetcher: OnlineFetcher,
    cacheDir: File,
    val name: String,
    private val box: PackBounds,
    zooms: IntRange,
    private val cancelled: () -> Boolean,
) {
    private val dir = File(cacheDir, "local-build/$name").apply { deleteRecursively(); mkdirs() }
    private val demZooms = maxOf(zooms.first, DEM_MIN_ZOOM)..minOf(zooms.last, direct.maxZoom)
    private val osmZooms = zooms.first..minOf(zooms.last, OnlineTiles.OSM_MAX_ZOOM)
    private val demTiles = demZooms.flatMap { tiles(it) }
    private val osmTiles = osmZooms.flatMap { tiles(it) }
    private val done = AtomicInteger()
    private val failed = AtomicInteger()
    @Volatile private var phase = "dem"
    private val started = System.currentTimeMillis()
    private var demWritten = 0
    private var osmWritten = 0
    private val bytesAtStart = direct.http.bytes.get()

    private fun tiles(z: Int): List<Triple<Int, Int, Int>> {
        val n = 1 shl z
        val x0 = floor(WebMercator.lonToX(box.minLon, z)).toInt().coerceIn(0, n - 1)
        val x1 = floor(WebMercator.lonToX(box.maxLon, z)).toInt().coerceIn(0, n - 1)
        val y0 = floor(WebMercator.latToY(box.maxLat, z)).toInt().coerceIn(0, n - 1)
        val y1 = floor(WebMercator.latToY(box.minLat, z)).toInt().coerceIn(0, n - 1)
        return (y0..y1).flatMap { y -> (x0..x1).map { x -> Triple(z, x, y) } }
    }

    fun outputs(): List<File> = listOf(PackRole.DEM, PackRole.VECTOR).map { File(dir, PackNames.fileName(name, it)) }.filter { it.isFile }

    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** Builds both archives; [progress] gets [json] as it goes. Throws when nothing could be fetched. */
    fun run(progress: (Map<String, Any?>) -> Unit): Map<String, Any?> {
        val pool = Executors.newFixedThreadPool(DirectSources.MAX_TILES_IN_FLIGHT) { r -> Thread(r, "local-build").apply { isDaemon = true } }
        try {
            // DEM: rendered on the phone (or from the online cache).
            val demWriter = PmtilesWriter(TileType.PNG)
            val provenance = LinkedHashSet<String>()
            val written = java.util.TreeSet<Int>()
            fetchAll(pool, demTiles, "dem", ".png", progress) { (z, x, y), bytes ->
                demWriter.addTile(z, x, y, bytes)
                direct.provenanceOf(z, x, y)?.let { provenance += it }
                written += z
                demWritten++
            }
            if (cancelled()) return json()
            if (demWritten == 0) throw IOException("no elevation tiles could be fetched for the box (offline?)")
            val ids = SourceRegistry.builtIn.ordered(provenance.toList().ifEmpty { listOf("copernicus") })
            writeArchive(File(dir, PackNames.fileName(name, PackRole.DEM))) { sink ->
                demWriter.finish(
                    sink, demMetadata(ids, written.first(), written.last()), box.minLon, box.minLat, box.maxLon, box.maxLat,
                    centerZoom = (written.first() + written.last() + 1) / 2, minZoom = written.first(), maxZoom = written.last(),
                )
            }

            // Vector: OpenFreeMap tiles as they come (OpenMapTiles schema, not the outdoors profile).
            phase = "osm"
            done.set(0)
            val osmWriter = OsmPackWriter(::gzip)
            fetchAll(pool, osmTiles, "osm", ".pbf", progress) { (z, x, y), bytes ->
                if (osmWriter.add(z, x, y, bytes)) osmWritten++
            }
            if (cancelled()) return json()
            if (osmWriter.tiles > 0) {
                val registry = SourceRegistry.builtIn
                val osmIds = registry.ordered(DirectSources.OSM_IDS)
                val meta = OsmPackMetadata(
                    name = "$name-outdoors", bounds = listOf(box.minLon, box.minLat, box.maxLon, box.maxLat),
                    minZoom = osmZooms.first, maxZoom = osmZooms.last, vectorLayers = runCatching { direct.openFreeMapLayers() }.getOrDefault(emptyList()),
                    attribution = registry.attribution(osmIds), attributionHtml = registry.attributionHtml(osmIds),
                    provenance = osmIds, license = registry.getValue("osm").license, sourceBuild = runCatching { direct.openFreeMap().second }.getOrNull(),
                )
                writeArchive(File(dir, PackNames.fileName(name, PackRole.VECTOR))) { sink -> osmWriter.finish(sink, meta) }
            }
            phase = "written"
            return json().also(progress)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun fetchAll(
        pool: java.util.concurrent.ExecutorService, tiles: List<Triple<Int, Int, Int>>, route: String, ext: String,
        progress: (Map<String, Any?>) -> Unit, add: (Triple<Int, Int, Int>, ByteArray) -> Unit,
    ) {
        val futures = tiles.map { t ->
            pool.submit(Callable {
                if (cancelled()) null else {
                    val f = fetcher.get(route, "${t.first}/${t.second}/${t.third}$ext", if (route == "osm") OnlineFetcher::gunzipIfNeeded else { b -> b })
                    done.incrementAndGet()
                    when (f) {
                        is Fetch.Hit -> f.bytes
                        Fetch.Missing -> null
                        is Fetch.Unavailable -> {
                            failed.incrementAndGet()
                            null
                        }
                    }
                }
            })
        }
        // Writers are not thread-safe: tiles are added on this thread, in order.
        for ((i, fut) in futures.withIndex()) {
            val bytes = fut.get() ?: continue
            if (route == "dem" || bytes.isNotEmpty()) add(tiles[i], bytes)
            if (i % 8 == 0) progress(json())
        }
    }

    private fun writeArchive(file: File, write: (ByteSink) -> Unit) {
        val part = File(file.parentFile, file.name + ".part")
        BufferedOutputStream(part.outputStream(), 1 shl 16).use { os -> write(ByteSink { os.write(it) }) }
        if (!part.renameTo(file)) throw IOException("rename ${part.name} failed")
    }

    /** The DEM archive's metadata, with the keys server-built packs carry (`TileSetMetadata`). */
    private fun demMetadata(ids: List<String>, minZoom: Int, maxZoom: Int): String {
        val registry = SourceRegistry.builtIn
        val licences = ids.mapNotNull { registry[it]?.license }.distinct()
        return MiniJson.encode(
            linkedMapOf(
                "name" to "$name-dem", "format" to "png", "encoding" to "terrarium", "scheme" to "xyz", "tileSize" to 256,
                "bounds" to listOf(box.minLon, box.minLat, box.maxLon, box.maxLat), "minzoom" to minZoom, "maxzoom" to maxZoom,
                "verticalDatum" to "EGM2008", "noDataMeters" to 0.0, "source" to "best (built on the phone)",
                "attribution" to registry.attribution(ids), "attributionHtml" to registry.attributionHtml(ids),
                "provenance" to ids, "license" to licences.joinToString(" AND ").ifEmpty { null },
            ),
        )
    }

    fun json(): Map<String, Any?> {
        val total = if (phase == "dem") demTiles.size else osmTiles.size
        val share = if (total == 0) 1.0 else done.get().toDouble() / total
        // DEM rendering is most of the time: it is the first 80 percent.
        val percent = when (phase) {
            "dem" -> share * 80
            "osm" -> 80 + share * 20
            else -> 100.0
        }
        return linkedMapOf(
            "id" to "local", "status" to if (phase == "written") "done" else "running", "phase" to phase, "percent" to percent,
            "local" to true, "elapsedMs" to System.currentTimeMillis() - started,
            "dem" to mapOf("zooms" to listOf(demZooms.first, demZooms.last), "tiles" to demTiles.size, "written" to demWritten),
            "osm" to mapOf("zooms" to listOf(osmZooms.first, osmZooms.last), "tiles" to osmTiles.size, "written" to osmWritten),
            "failedTiles" to failed.get(), "sourceBytes" to direct.http.bytes.get() - bytesAtStart,
            "osmProfile" to OsmPackWriter.PROFILE_OPENMAPTILES, "osmSource" to "openfreemap",
        )
    }

    companion object {
        const val DEM_MIN_ZOOM = DirectSources.DEM_MIN_ZOOM


        fun gzip(input: ByteArray): ByteArray =
            ByteArrayOutputStream(input.size / 2 + 64).also { out -> GZIPOutputStream(out).use { it.write(input) } }.toByteArray()
    }
}
