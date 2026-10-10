package io.github.jasondorjeshort.dystopo.app

import android.graphics.Bitmap
import io.github.jasondorjeshort.dystopo.dem.TileWindows
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreSources
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import io.github.jasondorjeshort.dystopo.tiles.BitmapImageDecoder
import io.github.jasondorjeshort.dystopo.tiles.ContourTileOptions
import io.github.jasondorjeshort.dystopo.tiles.ContourTiles
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Counters for one tile source, reported by `/stats`. */
class SourceCounters {
    val served = AtomicLong()
    val bytes = AtomicLong()
    val notFound = AtomicLong()
    val errors = AtomicLong()

    fun json() = mapOf("served" to served.get(), "bytes" to bytes.get(), "notFound" to notFound.get(), "errors" to errors.get())
}

/** Min/avg/max of a series of millisecond timings, plus the most recent samples. */
class Timing(private val keepRecent: Int = 20) {
    private var count = 0L
    private var total = 0.0
    private var min = Double.POSITIVE_INFINITY
    private var max = 0.0
    private val recent = ArrayDeque<Map<String, Any?>>()

    @Synchronized
    fun add(ms: Double, detail: Map<String, Any?>) {
        count++
        total += ms
        if (ms < min) min = ms
        if (ms > max) max = ms
        recent.addLast(detail + ("ms" to round1(ms)))
        while (recent.size > keepRecent) recent.removeFirst()
    }

    @Synchronized
    fun json(): Map<String, Any?> = mapOf(
        "count" to count,
        "minMs" to if (count == 0L) null else round1(min),
        "avgMs" to if (count == 0L) null else round1(total / count),
        "maxMs" to if (count == 0L) null else round1(max),
        "totalMs" to round1(total),
        "recent" to recent.toList(),
    )
}

fun round1(d: Double) = Math.round(d * 10) / 10.0

/** HTTP-ish result of a tile request. */
class TileResult(val status: Int, val contentType: String, val body: ByteArray) {
    companion object {
        fun notFound() = TileResult(404, "text/plain", "not found\n".toByteArray())
    }
}

/**
 * Serves the region pack to MapLibre: DEM tiles (stored PNGs, or synthesized from an ancestor where
 * the pack has none, like the browser demo's fallback), OSM vector tiles (decompressed), contour
 * tiles generated on the device from the DEM ([ContourTiles], cached on disk), glyphs and the style.
 */
class TileService(val packs: PackSet, private val cacheDir: File, private val port: Int) {
    val dem = SourceCounters()
    val demSynthesized = AtomicLong()
    val osm = SourceCounters()
    val contours = SourceCounters()
    val glyphs = SourceCounters()
    val contourGen = Timing()
    val contourCacheHits = AtomicLong()
    val demSynthesis = Timing()

    private val contourOptions = ContourTileOptions()
    private val contourCache = File(cacheDir, "contours/v1-${contourOptions.units.symbol}-${contourOptions.tileSize}").apply { mkdirs() }

    /** Decoded DEM tiles shared by contour generation and DEM synthesis; access under [pyramidLock]. */
    private val pyramid: DemPyramid? = packs.dem?.let { pack ->
        DemPyramid(pack.header.minZoom, pack.header.maxZoom, BitmapImageDecoder, cacheTiles = 48) { z, x, y -> pack.tile(z, x, y) }
    }
    private val pyramidLock = Any()

    val demMinZoom get() = packs.dem?.header?.minZoom ?: 8
    val demMaxZoom get() = packs.dem?.header?.maxZoom ?: 14

    fun pyramidJson(): Map<String, Any?> = synchronized(pyramidLock) {
        val p = pyramid ?: return mapOf("loaded" to false)
        mapOf(
            "loaded" to true,
            "cachedTiles" to p.cachedTiles,
            "cachedTileBytes" to p.cachedTiles.toLong() * p.tileSize * p.tileSize * 4,
            "tilesDecoded" to p.tilesDecoded,
            "cacheHits" to p.cacheHits,
        )
    }

    fun style(): String {
        val base = "http://127.0.0.1:$port"
        val osmPack = packs.osm
        val attribution = listOfNotNull(osmPack?.attribution, packs.dem?.attribution).filter { it.isNotEmpty() }.joinToString(" | ")
        return MapLibreStyle(
            MapLibreSources(
                demTiles = "$base/dem/{z}/{x}/{y}.png",
                demMinZoom = demMinZoom,
                demMaxZoom = demMaxZoom,
                contourTiles = "$base/contours/{z}/{x}/{y}.pbf",
                // Contour tile z is traced from DEM zoom z+1 (512-px vector tiles), and overzoomed DEM is fine
                // two levels past the pack: 20 ft contours at z15 from a z14 pack are smooth bilinear lines.
                contourMinZoom = (demMinZoom - 1).coerceAtLeast(0),
                contourMaxZoom = demMaxZoom + 1,
                osmTiles = osmPack?.let { "$base/osm/{z}/{x}/{y}.pbf" },
                osmMinZoom = osmPack?.header?.minZoom ?: 0,
                osmMaxZoom = osmPack?.header?.maxZoom ?: 14,
                glyphs = "$base/glyphs/{fontstack}/{range}.pbf",
                attribution = attribution,
            ),
        ).json()
    }

    fun demTile(z: Int, x: Int, y: Int): TileResult {
        val pack = packs.dem ?: return TileResult.notFound().also { dem.notFound.incrementAndGet() }
        val stored = pack.tile(z, x, y)
        if (stored != null) return ok(dem, "image/png", stored)
        // Not in the pack: synthesize from the nearest ancestor so hillshade continues past a deep level's edge.
        val started = System.nanoTime()
        val grid = synchronized(pyramidLock) {
            val p = pyramid ?: return TileResult.notFound()
            val ts = p.tileSize
            if (!p.covers(z, x * ts, y * ts, ts, ts)) null else p.window(z, x * ts, y * ts, ts, ts)
        }
        if (grid == null || grid.values.all { it.isNaN() }) {
            dem.notFound.incrementAndGet()
            return TileResult.notFound()
        }
        val argb = TileWindows.encodeTerrarium(grid)
        val bitmap = Bitmap.createBitmap(argb, grid.width, grid.height, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream(64 * 1024).also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        demSynthesized.incrementAndGet()
        demSynthesis.add((System.nanoTime() - started) / 1e6, mapOf("tile" to "$z/$x/$y"))
        return ok(dem, "image/png", png)
    }

    fun osmTile(z: Int, x: Int, y: Int): TileResult {
        val pack = packs.osm ?: return TileResult.notFound().also { osm.notFound.incrementAndGet() }
        val bytes = pack.tile(z, x, y) ?: return TileResult.notFound().also { osm.notFound.incrementAndGet() }
        return ok(osm, "application/x-protobuf", bytes)
    }

    fun contourTile(z: Int, x: Int, y: Int): TileResult {
        val file = File(contourCache, "$z/$x/$y.pbf")
        if (file.isFile) {
            contourCacheHits.incrementAndGet()
            return ok(contours, "application/x-protobuf", file.readBytes())
        }
        val p = pyramid ?: return TileResult.notFound().also { contours.notFound.incrementAndGet() }
        val started = System.nanoTime()
        val dz = ContourTiles.demZoom(z, contourOptions, p.tileSize)
        val w = ContourTiles.windowFor(x, y, contourOptions)
        // Sampling (PNG decode, cache) is serialized; tracing and encoding run in parallel on the server threads.
        val grid = synchronized(pyramidLock) { p.window(dz, w[0], w[1], w[2], w[3]) }
        val sampled = System.nanoTime()
        val tile = ContourTiles.build(grid, dz, contourOptions)
        val done = System.nanoTime()
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.${Thread.currentThread().id}.tmp")
        tmp.writeBytes(tile.bytes)
        if (!tmp.renameTo(file)) tmp.delete()
        contourGen.add(
            (done - started) / 1e6,
            mapOf(
                "tile" to "$z/$x/$y", "demZoom" to dz, "sampleMs" to round1((sampled - started) / 1e6),
                "traceEncodeMs" to round1((done - sampled) / 1e6), "lines" to tile.lines, "points" to tile.points,
                "bytes" to tile.bytes.size, "interval" to tile.spec.interval,
            ),
        )
        return ok(contours, "application/x-protobuf", tile.bytes)
    }

    fun glyph(fontstack: String, range: String): TileResult {
        val dir = packs.glyphDir
        // MapLibre asks for comma-separated font stacks; serve the first font we have.
        val file = fontstack.split(",").map { it.trim() }.firstNotNullOfOrNull { font ->
            dir?.let { File(it, "$font/$range.pbf") }?.takeIf { f -> f.isFile && f.canonicalPath.startsWith(dir.canonicalPath) }
        }
        if (file == null) {
            // An empty glyph set: the labels in this range draw without these characters instead of erroring.
            glyphs.notFound.incrementAndGet()
            return TileResult(200, "application/x-protobuf", ByteArray(0))
        }
        return ok(glyphs, "application/x-protobuf", file.readBytes())
    }

    fun clearContourCache(): Int {
        var n = 0
        contourCache.walkBottomUp().forEach { if (it != contourCache && it.delete()) n++ }
        return n
    }

    private fun ok(c: SourceCounters, type: String, bytes: ByteArray): TileResult {
        c.served.incrementAndGet()
        c.bytes.addAndGet(bytes.size.toLong())
        return TileResult(200, type, bytes)
    }
}
