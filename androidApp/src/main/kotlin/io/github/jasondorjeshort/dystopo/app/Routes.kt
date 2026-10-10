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
 * - `GET /health`: JSON (server load and refusals, packs, map state: `map.idle` with
 *   `map.notIdleBecause`, see [MapController.idleBlockers]).
 * - `GET /screenshot`: PNG of the map (MapLibre snapshot). `?mode=window` captures the app's content
 *   area, map plus overlays (see [MapController.windowPng]); `?wait=<ms>` (default 15000) waits for the
 *   map to go idle and then for two snapshots in a row to agree ([MapController.stableSnapshot]);
 *   `?stable=0` skips the pixel check, `?wait=0` takes the picture at once. `/health`
 *   `map.lastScreenshotIdle` says whether it settled, `map.lastScreenshot` how (rounds, changed share, ms).
 * Debug routes run on their own small thread pool, so they answer while tiles are generating ([LocalServer]).
 * - `GET /camera`; `POST /camera` with query parameters or a JSON body: lat, lon, zoom, bearing, tilt.
 * - `GET /stats`: tiles served per source, contour generation timings (min/avg/max and recent tiles),
 *   cache hits, DEM decode cache, memory (Java heap, native heap, PSS), APK and pack sizes.
 * - `POST /location` (lat, lon[, accuracy, bearing]): injects a location fix; the puck shows it and the
 *   device's own location engine stays detached until `POST /location?device=1`. `GET /location`.
 * - `GET /settings`, `POST /settings?textScale=1.5&units=m&warmup=0`, `POST /settings/ui`: user settings ([SettingsRoutes]).
 * - `GET /features` (alias `/labels`), `GET /features/text`: what the map drew ([FeatureRoutes]).
 * - `POST /reload`: reopens the packs and reloads the style; `POST /cache/clear`: drops cached contour tiles.
 */
class Routes(private val app: DystopoApp) {
    private val tile = Regex("^/(dem|osm|contours)/(-?\\d+)/(-?\\d+)/(-?\\d+)\\.(png|pbf|mvt)$")
    private val settings = SettingsRoutes(app)
    private val glyph = Regex("^/glyphs/([^/]+)/(\\d+-\\d+)\\.pbf$")

    init {
        // The map's idle check counts the tile requests the server is still working on or has queued.
        val tileKinds = listOf("dem", "osm", "contours", "glyphs", "shade") // T47: shade
        app.map.pendingTiles = { tileKinds.sumOf { app.server.inFlight(it) } + app.server.queued }
        app.map.lastTileAt = { tileKinds.maxOf { app.server.lastDone(it) } }
    }

    fun handle(r: Request): Response {
        tile.matchEntire(r.path)?.let { m ->
            val (kind, zs, xs, ys) = m.destructured
            val (zxy, error) = RequestChecks.tile(zs, xs, ys)
            val (z, x, y) = zxy ?: return Response.text("$error\n", 400)
            val t = app.tiles
            val res = when (kind) {
                "dem" -> t.demTile(z, x, y)
                "osm" -> t.osmTile(z, x, y)
                else -> t.contourTile(z, x, y)
            }
            return Response(res.status, res.contentType, res.body)
        }
        glyph.matchEntire(r.path)?.let { m ->
            val res = app.tiles.glyph(m.groupValues[1], m.groupValues[2])
            return Response(res.status, res.contentType, res.body)
        }
        ShadeRoutes.handle(app, r)?.let { return it } // T47: /shade/{z}/{x}/{y}.png, GET /shade
        if (r.path == "/style.json") return Response(200, "application/json", app.tiles.style().toByteArray())
        if (!BuildConfig.DEBUG_ENDPOINTS) return Response.notFound()
        AttributionRoutes.handle(app, r)?.let { return it }
        TrackRoutes.handle(app, r)?.let { return it } // T31: /tracks, /location/replay; observes POST /location
        settings.handle(r)?.let { return it }
        ContourLabelRoutes(app).handle(r)?.let { return it } // T36: GET /contour-labels
        PacksRoutes.handle(app, r)?.let { return it }
        SearchRoutes.handle(app, r)?.let { return it } // T39: /search, /search/status, /search/build, /search/ui, /search/select
        OnlineRoutes.handle(app, r)?.let { return it } // T38: /online, /online/download
        SyncRoutes.handle(app, r)?.let { return it } // T44: POST /sync?with=, /sync/status, /sync/settings, /sync/field
        return when (r.method to r.path) {
            "GET" to "/health", "GET" to "/" -> Response.json(health())
            "GET" to "/stats" -> Response.json(stats())
            "GET" to "/features", "GET" to "/features/text", "GET" to "/labels" -> FeatureRoutes(app).handle(r)
            "GET" to "/camera" -> Response.json(app.map.camera())
            "POST" to "/camera" -> {
                val p = params(r)
                Response.json(app.map.setCamera(p.d("lat"), p.d("lon"), p.d("zoom"), p.d("bearing"), p.d("tilt")))
            }
            "GET" to "/screenshot" -> screenshot(r)
            "GET" to "/location" -> Response.json(app.map.locationJson())
            "POST" to "/location" -> {
                val p = params(r)
                if (p.d("device") == 1.0) return Response.json(app.map.useDeviceLocation())
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
        val window = r.query["mode"] == "window"
        if (wait > 0 && r.query["stable"] != "0") {
            // Default: idle, then two snapshots in a row that agree (MapController.stableSnapshot).
            val s = app.map.stableSnapshot(wait)
            lastScreenshotIdle = s.stable // a capture that never settled still returns the PNG; /health tells which it was
            app.map.lastScreenshot = s.json() + mapOf("mode" to if (window) "window" else "map")
            val png = if (window) app.map.windowPng() else app.map.pngOf(s.bitmap)
            s.bitmap.recycle()
            return Response(200, "image/png", png)
        }
        val idle = if (wait > 0) app.map.awaitIdle(wait) else app.map.idle
        val png = if (window) app.map.windowPng() else app.map.mapSnapshotPng()
        lastScreenshotIdle = idle
        app.map.lastScreenshot = mapOf("idle" to idle, "stable" to null, "mode" to if (window) "window" else "map")
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
            "attribution" to RequestChecks.htmlToText(it.attribution),
        )
    }

    private fun health(): Map<String, Any?> {
        val t = app.tiles
        val m = app.map
        return mapOf(
            "ok" to true,
            "app" to mapOf("version" to BuildConfig.VERSION_NAME, "debug" to BuildConfig.DEBUG, "sdk" to Build.VERSION.SDK_INT, "device" to Build.MODEL),
            "server" to mapOf("port" to BuildConfig.SERVER_PORT) + app.server.stats(),
            "uptimeMs" to SystemClock.elapsedRealtime() - app.startedAt,
            "packs" to mapOf("dem" to packJson(t.packs.dem), "osm" to packJson(t.packs.osm), "glyphs" to t.packs.glyphDir?.path),
            "map" to mapOf(
                "attached" to (m.mapView != null), "ready" to (m.map != null), "styleLoaded" to m.styleLoaded,
                "styleError" to m.styleError, "framesRendered" to m.framesRendered,
                "lastScreenshotIdle" to lastScreenshotIdle,
            ) + m.idleJson(),
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
            "shadeGeneration" to t.shade.json(), // T47
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

    companion object {
        private val MAP_PREFIXES = listOf("/dem/", "/osm/", "/contours/", "/glyphs/", "/sprite", "/shade/") // T47: /shade/

        /** MapLibre's own requests (tiles, glyphs, sprites, the style) run on the tile pool; everything else on the debug pool. */
        fun isMapRequest(r: Request): Boolean = r.path == "/style.json" || MAP_PREFIXES.any { r.path.startsWith(it) }
    }
}
