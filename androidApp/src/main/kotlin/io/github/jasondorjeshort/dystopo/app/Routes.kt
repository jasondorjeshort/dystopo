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
 * Debug builds only ([BuildConfig.DEBUG_ENDPOINTS]), for agents through `adb forward`; T57: on the debug
 * server's random port (logcat line `debug server on port N token T`), or on 8686 with `debugFixedPort=1`:
 * - `GET /health`: JSON (server load and refusals, packs, map state: `map.idle` with
 *   `map.notIdleBecause`, see [MapController.idleBlockers]; T123: `map.activity`, `started` or why not).
 * - `GET /screenshot`: PNG of the map (MapLibre snapshot). `?mode=window` captures the app's content
 *   area, map plus overlays (see [MapController.windowPng]); `?wait=<ms>` (default 15000) waits for the
 *   map to go idle and then for two snapshots in a row to agree ([MapController.stableSnapshot]);
 *   `?stable=0` skips the pixel check, `?wait=0` takes the picture at once. `/health`
 *   `map.lastScreenshotIdle` says whether it settled, `map.lastScreenshot` how (rounds, changed share, ms).
 * Debug routes run on their own small thread pool, so they answer while tiles are generating ([LocalServer]).
 * - `GET /camera`; `POST /camera` with query parameters or a JSON body: lat, lon, zoom, bearing, tilt.
 *   T93: `POST /camera/recenter` (the recentre button's tap) and the button's state in `GET /camera`
 *   ([io.github.jasondorjeshort.dystopo.app.controls.ControlRoutes]).
 * - `GET /stats`: tiles served per source, contour generation timings (min/avg/max and recent tiles),
 *   cache hits, DEM decode cache, memory (Java heap, native heap, PSS), APK and pack sizes; `server.pools` the
 *   loopback server's queue waits and run times per pool (T63: `tile`, `generation`, `debug`; T88: `wait`, up to 48
 *   workers and no queue, where DEM, contour and shade requests waiting on the phone's own source reads run, with
 *   `maxThreads`, `threads`, `largest` and `active`).
 * - `POST /location` (lat, lon[, accuracy, bearing]): injects a location fix; the puck shows it and the
 *   device's own location engine stays detached until `POST /location?device=1`. `GET /location`.
 * - `GET /settings`, `POST /settings?textScale=1.5&units=m&warmup=0`, `POST /settings/ui`, T103 `GET /settings/layout` and `POST /settings/open?group=`: user settings and the sheet's groups ([SettingsRoutes]).
 * - `GET /features` (alias `/labels`), `GET /features/text`: what the map drew ([FeatureRoutes]).
 * - `POST /reload`: reopens the packs and reloads the style; `POST /cache/clear`: drops cached contour tiles.
 * - T123 `GET /stats/requests[?limit=N][&format=jsonl]`: the request log ([RequestLog]): one line per request the
 *   loopback servers answered since start (time, method, path without the query, route, the pack that answered a tile
 *   or `online:<source key>`, status, bytes, ms), the last 4096 kept in memory; `capacity`, `total`, `kept`,
 *   `dropped`, `perRoute` (requests, bytes, statuses, maxMs) and the newest `limit` lines (default 200); `jsonl`
 *   gives every kept line (or the newest `limit`) as `devtool cache-replay --log` reads a server log.
 * - T123, the background guard: the routes that need the activity on screen ([RequestChecks.needsActivity]: the
 *   screenshot, the feature routes, the help routes, opening the About screen; T128: the five sheet-driving UI routes,
 *   `POST /settings/ui`, `/settings/open`, `/search/ui`, `/storage/ui`, `/areas/ui`) answer 503 with
 *   `{"error", "reason", "route"}` at once while the activity is stopped or gone ([MapController.backgroundReason]),
 *   instead of hanging until their own timeouts.
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

    /**
     * T49: the per-launch token gates every route ([LoopbackToken]); `t` is stripped before routing.
     * T57: [debugRoutes] is false on the tile server (port 8686) unless `debugFixedPort` is set, and always in
     * release builds: there only map routes answer (404 for the rest, with or without a token). The token-only
     * `/health` bootstrap exists only with `debugFixedPort`; otherwise the token is in logcat
     * ([DystopoApp.DEBUG_TAG]) with the debug server's port.
     */
    fun handle(raw: Request, debugRoutes: Boolean = BuildConfig.DEBUG_ENDPOINTS): Response {
        val debug = debugRoutes && BuildConfig.DEBUG_ENDPOINTS
        if (!debug && !isMapRequest(raw)) return Response.notFound()
        if (!BuildConfig.DEBUG_ENDPOINTS) return admitAndRoute(raw, debug)
        // T123: the request log (debug builds; `GET /stats/requests`). A handler that throws is logged as LocalServer answers it.
        val started = System.currentTimeMillis()
        val res = try {
            admitAndRoute(raw, debug)
        } catch (e: Exception) {
            requestLog.add(raw.method, raw.path, 500, 0, started)
            throw e
        }
        requestLog.add(raw.method, raw.path, res.status, res.body.size, started, if (res.status == 200) packOf(raw.path) else null)
        return res
    }

    private fun admitAndRoute(raw: Request, debug: Boolean): Response {
        val (r, answer) = app.token.admit(raw, debug && app.fixedDebugPort)
        return answer ?: route(r!!, debug)
    }

    /** T123: the phone's request log, one line per request (debug builds), `GET /stats/requests`. */
    val requestLog = RequestLog()

    private val anyTile = Regex("^/(dem|osm|contours|shade)/(\\d+)/(\\d+)/(\\d+)\\.\\w+$")

    /**
     * T123: which pack a tile at [path] came from, for the request log: the region pack that holds it, or
     * `online:<source key>` where the online fallback answers (the same coverage checks as [TileService]); null otherwise.
     */
    private fun packOf(path: String): String? = runCatching {
        val m = anyTile.matchEntire(path) ?: return null
        val kind = m.groupValues[1]
        val (z, x, y) = m.groupValues.drop(2).map { it.toInt() }
        val t = app.tiles
        val o = t.online
        val online = o?.let { "online:${it.fetcher.serverKey}" }
        // A Pack's own name is its role (`dem`, `osm`); the log names the region it belongs to.
        fun region(p: Pack?): String? = p?.let { pack -> t.packs.regions.firstOrNull { it.dem === pack || it.osm === pack }?.name ?: pack.file.name }
        when (kind) {
            "osm" -> if (o != null && !o.packCoversOsm(z, x, y)) online else region(t.packs.osmCandidates(z, x, y).firstOrNull())
            "contours" -> if (o != null && !o.packCoversContour(z, x, y)) online else region(t.packs.demFor(z, x, y, io.github.jasondorjeshort.dystopo.packs.PackRoleZooms.CONTOUR))
            else -> if (o != null && !o.packCoversDem(z, x, y)) online else region(t.packs.demFor(z, x, y))
        }
    }.getOrNull()

    private fun route(r: Request, debug: Boolean): Response {
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
        if (!debug) return Response.notFound()
        // T123: routes that need the activity on screen (its window, the map's frames, Compose layout) hung until their own
        // timeouts with the app in the background; one guard here answers them 503 with the reason instead.
        if (RequestChecks.needsActivity(r.method, r.path)) app.map.backgroundReason()?.let { why ->
            return Response.json(mapOf("error" to "the app is in the background", "reason" to why, "route" to r.path), 503)
        }
        AttributionRoutes.handle(app, r)?.let { return it }
        NavRoutes.handle(app, r)?.let { return it } // T61: /nav, /nav/status, /nav/start|stop|camera|heading; observes POST /location?bearing=
        TrackRoutes.handle(app, r)?.let { return it } // T31: /tracks, /location/replay; observes POST /location
        settings.handle(r)?.let { return it }
        ContourLabelRoutes(app).handle(r)?.let { return it } // T36: GET /contour-labels
        io.github.jasondorjeshort.dystopo.app.firstrun.FirstRunRoutes.handle(app, r)?.let { return it } // T79: /firstrun, /packs/import (before the pack routes)
        PacksRoutes.handle(app, r)?.let { return it }
        SearchRoutes.handle(app, r)?.let { return it } // T39: /search, /search/status, /search/build, /search/ui, /search/select
        OnlineRoutes.handle(app, r)?.let { return it } // T38: /online, /online/download
        io.github.jasondorjeshort.dystopo.app.storage.StorageRoutes.handle(app, r)?.let { return it } // T74: /storage, /storage/clear, /storage/ui
        SyncRoutes.handle(app, r)?.let { return it } // T44: POST /sync?with=, /sync/status, /sync/settings, /sync/field
        ShareRoutes.handle(app, r)?.let { return it } // T54: /share/start, /share/stop, /share/status, /share/connect, /share/qr
        RouteRoutes.handle(app, r)?.let { return it } // T45: /route, /route/status, /route/build, /route/longpress, /route/save, /route/gpx
        io.github.jasondorjeshort.dystopo.app.update.UpdateRoutes.handle(app, r)?.let { return it } // T83: /update, /update/check, /update/install
        io.github.jasondorjeshort.dystopo.app.areas.AreaRoutes.handle(app, r)?.let { return it } // T75: /areas, /areas/download, /areas/trim, /areas/prefetch
        io.github.jasondorjeshort.dystopo.app.controls.ControlRoutes.handle(app, r)?.let { return it } // T93: GET /camera, POST /camera/recenter
        io.github.jasondorjeshort.dystopo.app.controls.ScaleBarRoutes.handle(app, r)?.let { return it } // T101: GET /scalebar, POST /scalebar?both=
        io.github.jasondorjeshort.dystopo.app.help.HelpRoutes.handle(app, r)?.let { return it } // T109: GET /help, POST /help/open?key=, POST /help/close
        return when (r.method to r.path) {
            "GET" to "/health", "GET" to "/" -> Response.json(health())
            "GET" to "/stats" -> Response.json(stats())
            "GET" to "/stats/requests" -> { // T123
                val limit = r.query["limit"]?.let { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return Response.json(mapOf("error" to "limit must be a whole number >= 0"), 400) }
                if (r.query["format"] == "jsonl") Response(200, "application/x-ndjson", requestLog.jsonLines(limit ?: requestLog.capacity).toByteArray())
                else Response.json(requestLog.json(limit ?: RequestLog.DEFAULT_LIMIT))
            }
            "GET" to "/features", "GET" to "/features/text", "GET" to "/labels" -> FeatureRoutes(app).handle(r)
            "POST" to "/camera" -> {
                val p = params(r)
                p.d("zoom")?.let { io.github.jasondorjeshort.dystopo.app.online.DirectTrace.mark("camera z$it") } // T115: the direct timeline shows where a script's view starts
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
            "GET" to "/dem/decode-bench" -> Response.json(app.tiles.decodeBench(r.query)) // T42: DEM decoders side by side
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
            "server" to mapOf("port" to BuildConfig.SERVER_PORT, "tokenRefused" to app.token.refused.get()) + app.server.stats() + // T49
                mapOf("debugPort" to app.debugPort, "debugFixedPort" to app.fixedDebugPort, "debugServer" to app.debugServer?.stats()), // T57
            "uptimeMs" to SystemClock.elapsedRealtime() - app.startedAt,
            "packs" to mapOf("dem" to packJson(t.packs.dem), "osm" to packJson(t.packs.osm), "glyphs" to t.packs.glyphDir?.path, "primary" to t.packs.name, "inUse" to t.packs.json()), // T41: every pack on the map
            "map" to mapOf(
                "attached" to (m.mapView != null), "ready" to (m.map != null), "styleLoaded" to m.styleLoaded,
                "styleError" to m.styleError, "styleLoads" to m.styleLoads.get(), "framesRendered" to m.framesRendered, // T138: styleLoads
                "lastScreenshotIdle" to lastScreenshotIdle,
                "activity" to (m.backgroundReason() ?: "started"), // T123: what the background guard reads
            ) + m.idleJson(),
        )
    }

    private fun stats(): Map<String, Any?> {
        val t = app.tiles
        val rt = Runtime.getRuntime()
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val sys = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return mapOf(
            "tiles" to mapOf(
                "dem" to t.dem.json() + mapOf("synthesizedFromAncestor" to t.demSynthesized.get(), "synthesis" to t.demSynthesis.json()),
                "osm" to t.osm.json(),
                "contours" to t.contours.json() + mapOf("diskCacheHits" to t.contourCacheHits.get()),
                "glyphs" to t.glyphs.json(),
            ),
            "contourGeneration" to t.contourJson(),
            "shadeGeneration" to t.shade.json(), // T47
            "contourDiskCache" to ContourCache.json().let { c -> c + mapOf("tiles" to c["entries"]) }, // T41: one LRU over every pack
            "cliffs" to t.cliffs.json(), // T41: cliff lines turned downhill-right
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
                "packBytes" to t.packs.archives.sumOf { it.file.length() },
            ),
            "server" to mapOf("requests" to app.server.requests.get(), "pools" to app.server.poolStats()), // T63: queue waits per pool
            "counters" to mapOf("since" to "app start", "uptimeMs" to SystemClock.elapsedRealtime() - app.startedAt, "tileServiceOpens" to app.tileCounters.serviceOpens.get()), // T57
        )
    }

    companion object {
        private val MAP_PREFIXES = listOf("/dem/", "/osm/", "/contours/", "/glyphs/", "/sprite", "/shade/") // T47: /shade/

        /** MapLibre's own requests (tiles, glyphs, sprites, the style) run on the tile pool; everything else on the debug pool. */
        fun isMapRequest(r: Request): Boolean = r.path == "/style.json" || MAP_PREFIXES.any { r.path.startsWith(it) }
    }
}
