package io.github.jasondorjeshort.dystopo.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.io.File

/**
 * URL routing for the loopback server.
 *
 * Always: `/style.json`, `/dem/{z}/{x}/{y}.png`, `/osm/{z}/{x}/{y}.pbf`, `/contours/{z}/{x}/{y}.pbf`,
 * `/glyphs/{fontstack}/{range}.pbf`.
 *
 * Debug builds only ([BuildConfig.DEBUG_ENDPOINTS]), for agents through `adb forward`:
 * - `GET /health`: JSON (server, packs, map state).
 * - `GET /screenshot`: PNG of the map (MapLibre snapshot). `?mode=window` captures the whole window
 *   (PixelCopy, with overlays); `?wait=<ms>` (default 15000) waits for the map to go idle first;
 *   `/health` `map.lastScreenshotIdle` says whether it did (false: tiles were still loading).
 * - `GET /camera`; `POST /camera` with query parameters or a JSON body: lat, lon, zoom, bearing, tilt.
 * - `GET /stats`: tiles served per source, contour generation timings (min/avg/max and recent tiles),
 *   cache hits, DEM decode cache, memory (Java heap, native heap, PSS), APK and pack sizes.
 * - `POST /location` (lat, lon[, accuracy, bearing]): injects a location fix; `GET /location`.
 * - `POST /reload`: reopens the packs and reloads the style; `POST /cache/clear`: drops cached contour tiles.
 */
class Routes(private val app: DystopoApp) {
    private val tile = Regex("^/(dem|osm|contours)/(\\d+)/(\\d+)/(\\d+)\\.(png|pbf|mvt)$")
    private val glyph = Regex("^/glyphs/([^/]+)/(\\d+-\\d+)\\.pbf$")

    fun handle(r: Request): Response {
        tile.matchEntire(r.path)?.let { m ->
            val (kind, z, x, y) = m.destructured
            val t = app.tiles
            val res = when (kind) {
                "dem" -> t.demTile(z.toInt(), x.toInt(), y.toInt())
                "osm" -> t.osmTile(z.toInt(), x.toInt(), y.toInt())
                else -> t.contourTile(z.toInt(), x.toInt(), y.toInt())
            }
            return Response(res.status, res.contentType, res.body)
        }
        glyph.matchEntire(r.path)?.let { m ->
            val res = app.tiles.glyph(m.groupValues[1], m.groupValues[2])
            return Response(res.status, res.contentType, res.body)
        }
        if (r.path == "/style.json") return Response(200, "application/json", app.tiles.style().toByteArray())
        if (!BuildConfig.DEBUG_ENDPOINTS) return Response.notFound()
        AttributionRoutes.handle(app, r)?.let { return it }
        return when (r.method to r.path) {
            "GET" to "/health", "GET" to "/" -> Response.json(health())
            "GET" to "/stats" -> Response.json(stats())
            "GET" to "/camera" -> Response.json(app.map.camera())
            "POST" to "/camera" -> {
                val p = params(r)
                Response.json(app.map.setCamera(p.d("lat"), p.d("lon"), p.d("zoom"), p.d("bearing"), p.d("tilt")))
            }
            "GET" to "/screenshot" -> screenshot(r)
            "GET" to "/location" -> Response.json(app.map.locationJson())
            "POST" to "/location" -> {
                val p = params(r)
                val lat = p.d("lat") ?: return Response.json(mapOf("error" to "lat and lon required"), 400)
                val lon = p.d("lon") ?: return Response.json(mapOf("error" to "lat and lon required"), 400)
                Response.json(app.map.injectLocation(lat, lon, (p.d("accuracy") ?: 10.0).toFloat(), p.d("bearing")?.toFloat()))
            }
            "POST" to "/reload" -> {
                app.reload()
                Response.json(health())
            }
            "POST" to "/cache/clear" -> Response.json(mapOf("deleted" to app.tiles.clearContourCache()))
            else -> Response.notFound()
        }
    }

    private fun screenshot(r: Request): Response {
        val wait = r.query["wait"]?.toLongOrNull() ?: 15_000L
        val idle = if (wait > 0) app.map.awaitIdle(wait) else app.map.idle
        val png = if (r.query["mode"] == "window") app.map.windowPng() else app.map.mapSnapshotPng()
        lastScreenshotIdle = idle // a non-idle capture still returns the PNG; /health tells which it was
        return Response(200, "image/png", png)
    }

    @Volatile private var lastScreenshotIdle: Boolean? = null

    private class Params(val map: Map<String, Any?>) {
        fun d(key: String): Double? = when (val v = map[key]) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }

    /** Query parameters, overlaid by a JSON object body when there is one. */
    private fun params(r: Request): Params {
        val m = HashMap<String, Any?>(r.query)
        val body = r.bodyText.trim()
        if (body.startsWith("{")) m.putAll(MiniJson.parseObject(body))
        return Params(m)
    }

    private fun packJson(p: Pack?): Map<String, Any?>? = p?.let {
        val h = it.header
        mapOf(
            "file" to it.file.name, "bytes" to it.file.length(), "minZoom" to h.minZoom, "maxZoom" to h.maxZoom,
            "bounds" to listOf(h.minLon, h.minLat, h.maxLon, h.maxLat), "tileType" to h.tileType.label,
            "attribution" to it.attribution,
        )
    }

    private fun health(): Map<String, Any?> {
        val t = app.tiles
        val m = app.map
        return mapOf(
            "ok" to true,
            "app" to mapOf("version" to BuildConfig.VERSION_NAME, "debug" to BuildConfig.DEBUG, "sdk" to Build.VERSION.SDK_INT, "device" to Build.MODEL),
            "server" to mapOf("port" to BuildConfig.SERVER_PORT, "requests" to app.server.requests.get()),
            "uptimeMs" to SystemClock.elapsedRealtime() - app.startedAt,
            "packs" to mapOf("dem" to packJson(t.packs.dem), "osm" to packJson(t.packs.osm), "glyphs" to t.packs.glyphDir?.path),
            "map" to mapOf(
                "attached" to (m.mapView != null), "ready" to (m.map != null), "styleLoaded" to m.styleLoaded,
                "styleError" to m.styleError, "idle" to m.idle, "framesRendered" to m.framesRendered,
                "msSinceIdle" to if (m.lastIdleAt == 0L) null else SystemClock.elapsedRealtime() - m.lastIdleAt,
                "lastScreenshotIdle" to lastScreenshotIdle,
            ),
        )
    }

    private fun stats(): Map<String, Any?> {
        val t = app.tiles
        val rt = Runtime.getRuntime()
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val sys = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val cacheDir = File(app.cacheDir, "contours")
        val cached = cacheDir.walkTopDown().filter { it.isFile }.toList()
        return mapOf(
            "tiles" to mapOf(
                "dem" to t.dem.json() + mapOf("synthesizedFromAncestor" to t.demSynthesized.get(), "synthesis" to t.demSynthesis.json()),
                "osm" to t.osm.json(),
                "contours" to t.contours.json() + mapOf("diskCacheHits" to t.contourCacheHits.get()),
                "glyphs" to t.glyphs.json(),
            ),
            "contourGeneration" to t.contourJson(),
            "contourDiskCache" to mapOf("tiles" to cached.size, "bytes" to cached.sumOf { it.length() }),
            "demDecodeCache" to t.pyramidJson(),
            "memory" to mapOf(
                "javaHeapUsedBytes" to rt.totalMemory() - rt.freeMemory(),
                "javaHeapMaxBytes" to rt.maxMemory(),
                "nativeHeapAllocatedBytes" to Debug.getNativeHeapAllocatedSize(),
                "totalPssKb" to mi.totalPss,
                "pssJavaHeapKb" to mi.getMemoryStat("summary.java-heap")?.toLongOrNull(),
                "pssNativeHeapKb" to mi.getMemoryStat("summary.native-heap")?.toLongOrNull(),
                "pssGraphicsKb" to mi.getMemoryStat("summary.graphics")?.toLongOrNull(),
                "pssCodeKb" to mi.getMemoryStat("summary.code")?.toLongOrNull(),
                "deviceAvailBytes" to sys.availMem,
                "deviceTotalBytes" to sys.totalMem,
            ),
            "footprint" to mapOf(
                "apkBytes" to File(app.applicationInfo.sourceDir).length(),
                "packBytes" to listOfNotNull(t.packs.dem, t.packs.osm).sumOf { it.file.length() },
            ),
            "server" to mapOf("requests" to app.server.requests.get()),
        )
    }
}
