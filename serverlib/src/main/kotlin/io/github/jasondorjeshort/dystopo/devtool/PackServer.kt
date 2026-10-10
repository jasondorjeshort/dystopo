package io.github.jasondorjeshort.dystopo.devtool

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.jasondorjeshort.dystopo.attribution.PackAttribution
import io.github.jasondorjeshort.dystopo.attribution.PackMetadata
import io.github.jasondorjeshort.dystopo.dem.TileWindows
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreSources
import io.github.jasondorjeshort.dystopo.maplibre.MapLibreStyle
import io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader
import io.github.jasondorjeshort.dystopo.tiles.ArgbImage
import io.github.jasondorjeshort.dystopo.tiles.ContourTileOptions
import io.github.jasondorjeshort.dystopo.tiles.ContourTileStats
import io.github.jasondorjeshort.dystopo.tiles.ContourTiles
import io.github.jasondorjeshort.dystopo.tiles.DemPyramid
import io.github.jasondorjeshort.dystopo.tiles.ImageDecoder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * `devtool serve` without `--live` (moved here from ServeCommand, T52): the Android app's loopback tile
 * server on the desktop. Same routes and the same shared code paths ([MapLibreStyle], [DemPyramid],
 * [ContourTiles]) as `androidApp`, plus a page at `/` that draws the style with MapLibre GL JS, so the
 * phone style and the on-device contour tiles can be checked with `tools/demo-check.py --url
 * http://localhost:<port>/` without an emulator. `GET /stats` reports contour generation timings on
 * this machine (compare with the phone's); `GET /catalog` lists the packs in [outDir] ([PackCatalog]).
 */
class PackServer(
    private val dem: File,
    /** OSM vector PMTiles; null or missing for terrain only. */
    private val osm: File?,
    private val glyphDir: File,
    private val textScale: Double = 1.0,
    private val outDir: File = File("out"),
) {
    /** Starts serving on 127.0.0.1:[port] and prints one JSON line describing the server. */
    fun serve(port: Int): HttpServer {
        val demReader = PmtilesReader.open(FileRangeSource(dem), Gzip.decompressors)
        val osmReader = osm?.takeIf { it.isFile }?.let { PmtilesReader.open(FileRangeSource(it), Gzip.decompressors) }
        val lock = Any()
        val decoder = ImageDecoder { bytes ->
            val img = ImageIO.read(bytes.inputStream())
            ArgbImage(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
        }
        val h = demReader.header
        val pyramid = DemPyramid(h.minZoom, h.maxZoom, decoder, cacheTiles = 48) { z, x, y -> synchronized(lock) { demReader.tile(z, x, y) } }
        val options = ContourTileOptions()
        val base = "http://127.0.0.1:$port"
        val style = MapLibreStyle(
            MapLibreSources(
                demTiles = "$base/dem/{z}/{x}/{y}.png", demMinZoom = h.minZoom, demMaxZoom = h.maxZoom,
                contourTiles = "$base/contours/{z}/{x}/{y}.pbf", contourMinZoom = (h.minZoom - 1).coerceAtLeast(0), contourMaxZoom = h.maxZoom + 1,
                osmTiles = osmReader?.let { "$base/osm/{z}/{x}/{y}.pbf" },
                osmMinZoom = osmReader?.header?.minZoom ?: 0, osmMaxZoom = osmReader?.header?.maxZoom ?: 14,
                glyphs = "$base/glyphs/{fontstack}/{range}.pbf",
                sprite = "$base/sprite",
            ),
            textScale = textScale,
        ).json()
        // Fonts the style names but the glyph directory lacks: their labels would draw nothing.
        val missingFonts = MapLibreStyle.FONTS.filter { !File(glyphDir, "$it/0-255.pbf").isFile }
        val timings = ArrayList<Double>()
        val sampleTimings = ArrayList<Double>()
        val contourStats = ContourTileStats()
        val catalog = PackCatalog(outDir)
        val tileRe = Regex("^/(dem|osm|contours)/(\\d+)/(\\d+)/(\\d+)\\.(png|pbf)$")
        val glyphRe = Regex("^/glyphs/([^/]+)/(\\d+-\\d+)\\.pbf$")
        val centerLat = (h.minLat + h.maxLat) / 2
        val centerLon = (h.minLon + h.maxLon) / 2

        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 64)
        server.executor = Executors.newFixedThreadPool(4)
        server.createContext("/") { ex ->
            try {
                val path = ex.requestURI.path
                if (SyncRoutes.handle(ex)) return@createContext // T44: /sync/* (the always-on sync peer)
                // Static host for out/ (pack downloads in the app; ranges, ?kbps= throttle): see ServeStatic.
                if (path.startsWith("/out/")) return@createContext ServeStatic.serve(ex, outDir, path.removePrefix("/out/"))
                SpriteSheets.route(path)?.let { (bytes, type) -> return@createContext reply(ex, bytes, type) }
                tileRe.matchEntire(path)?.let { m ->
                    val (kind, zs, xs, ys) = m.destructured
                    val z = zs.toInt(); val x = xs.toInt(); val y = ys.toInt()
                    when (kind) {
                        "osm" -> reply(ex, osmReader?.let { r -> synchronized(lock) { r.tile(z, x, y) } }, "application/x-protobuf")
                        "dem" -> {
                            val stored = synchronized(lock) { demReader.tile(z, x, y) }
                            val bytes = stored ?: (
                                if (!pyramid.covers(z, x * 256, y * 256, 256, 256)) null else pyramid.window(z, x * 256, y * 256, 256, 256)
                                )?.takeIf { g -> !g.values.all { it.isNaN() } }?.let { g ->
                                val img = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
                                img.setRGB(0, 0, 256, 256, TileWindows.encodeTerrarium(g), 0, 256)
                                ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
                            }
                            reply(ex, bytes, "image/png")
                        }
                        else -> {
                            // The pyramid is thread-safe: tiles generate concurrently, up to the pool's 4 threads.
                            val started = System.nanoTime()
                            val concurrent = contourStats.begin()
                            val plan = ContourTiles.plan(z, x, y, options)
                            val grid = ContourTiles.sample(pyramid, plan)
                            val sampled = System.nanoTime()
                            val tile = ContourTiles.build(grid, plan, options)
                            contourStats.end(plan, tile, (System.nanoTime() - started) / 1e6, (sampled - started) / 1e6, concurrent)
                            synchronized(timings) {
                                timings += (System.nanoTime() - started) / 1e6
                                sampleTimings += (sampled - started) / 1e6
                            }
                            reply(ex, tile.bytes, "application/x-protobuf")
                        }
                    }
                    return@createContext
                }
                glyphRe.matchEntire(path)?.let { m ->
                    val file = m.groupValues[1].split(",").map { File(glyphDir, "${it.trim()}/${m.groupValues[2]}.pbf") }.firstOrNull { it.isFile }
                    reply(ex, file?.readBytes() ?: ByteArray(0), "application/x-protobuf")
                    return@createContext
                }
                when (path) {
                    "/style.json" -> reply(ex, style.toByteArray(), "application/json")
                    "/attribution" -> reply(ex, JsonWriter.write(PackAttribution.compose(listOfNotNull(osmReader?.let { PackMetadata("osm", osm!!.name, synchronized(lock) { it.metadata() }) }, PackMetadata("dem", dem.name, synchronized(lock) { demReader.metadata() })), if (glyphDir.isDirectory) listOf(PackAttribution.GLYPH_FONT_ID) else emptyList()).json()).toByteArray(), "application/json")
                    "/catalog", "/catalog.json" -> reply(ex, JsonWriter.write(catalog.json()).toByteArray(), "application/json")
                    "/stats" -> {
                        val t = synchronized(timings) { timings.toList() }
                        val s = synchronized(timings) { sampleTimings.toList() }
                        val json = JsonWriter.write(
                            mapOf(
                                "contourTiles" to t.size,
                                "minMs" to t.minOrNull(), "avgMs" to t.average().takeIf { t.isNotEmpty() }, "maxMs" to t.maxOrNull(),
                                "sampleAvgMs" to s.average().takeIf { s.isNotEmpty() },
                                "demTilesDecoded" to pyramid.tilesDecoded,
                                "contourGeneration" to contourStats.json(),
                            ),
                        )
                        reply(ex, json.toByteArray(), "application/json")
                    }
                    "/" -> reply(ex, page(centerLat, centerLon).toByteArray(), "text/html")
                    else -> reply(ex, null, "text/plain")
                }
            } catch (e: Exception) {
                reply(ex, (e.toString() + "\n").toByteArray(), "text/plain", status = 500)
            }
        }
        server.start()
        println(JsonWriter.write(mapOf("url" to "$base/", "style" to "$base/style.json", "stats" to "$base/stats", "dem" to dem.path, "osm" to (osm?.path ?: ""), "glyphs" to glyphDir.path, "missingFonts" to missingFonts, "textScale" to textScale)))
        return server
    }

    private fun reply(ex: HttpExchange, body: ByteArray?, type: String, status: Int = 200) {
        val code = if (body == null) 404 else status
        val bytes = body ?: "not found\n".toByteArray()
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
    }

    /** A bare MapLibre GL JS page over /style.json; `window.map` is what tools/demo-check.py inspects. */
    private fun page(lat: Double, lon: Double) = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Dystopo phone style</title>
<script src="https://unpkg.com/maplibre-gl@5.24.0/dist/maplibre-gl.js"></script>
<link href="https://unpkg.com/maplibre-gl@5.24.0/dist/maplibre-gl.css" rel="stylesheet">
<style>html,body{margin:0;height:100%;background:#f6f1e4}#map{position:absolute;inset:0}</style></head>
<body><div id="map"></div><script>
window.map = new maplibregl.Map({container: "map", style: "/style.json", center: [$lon, $lat], zoom: 12, hash: true});
</script></body></html>
"""
}
