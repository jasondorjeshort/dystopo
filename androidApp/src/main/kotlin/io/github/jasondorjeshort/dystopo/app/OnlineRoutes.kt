package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.online.OnlineView
import io.github.jasondorjeshort.dystopo.packs.PackBounds

/**
 * Debug routes for online mode (T38; registered with one line in [Routes]):
 * - `GET /online`: server, state (online / offline / unknown / none), the view and its source (pack,
 *   online, cached, none), fetcher counters (requests, cache hits, fetches, not found, errors, negative
 *   cache, backoff), the disk cache (entries, bytes, cap, evictions), contours made online, the download job.
 * - `POST /online/download[?bbox=w,s,e,n][&zooms=8-13][&name=][&profile=outdoors|openmaptiles]`: "Download this region" for the box
 *   (default: the visible map) through the server's `/build` and the pack downloader. `GET /online/download`:
 *   progress. `DELETE /online/download`: cancel.
 * - `POST /online/retry`: forget the backoff and recent failures; `POST /online/cache/clear`: empty the tile cache.
 * - The server itself is a setting: `POST /settings?server=http://10.0.2.2:8765` (empty for offline only), see [setServer].
 */
object OnlineRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/online" && !r.path.startsWith("/online/")) return null
        val s = OnlineService.get(app)
        return try {
            when (r.method to r.path) {
                "GET" to "/online" -> Response.json(s.json(OnlineView.current(app)?.json()))
                "GET" to "/online/download" -> Response.json(s.region.json())
                "POST" to "/online/download" -> Response.json(startDownload(app, s, r), 202)
                "DELETE" to "/online/download", "POST" to "/online/download/cancel" -> Response.json(s.region.cancel())
                "POST" to "/online/retry" -> {
                    s.fetcher?.retryNow()
                    Response.json(s.json(null))
                }
                "POST" to "/online/cache/clear" -> Response.json(mapOf("deleted" to s.cache.clear()))
                else -> Response.notFound()
            }
        } catch (e: IllegalArgumentException) {
            Response.json(mapOf("error" to e.message), 400)
        } catch (e: IllegalStateException) {
            Response.json(mapOf("error" to e.message), 409)
        }
    }

    private fun startDownload(app: DystopoApp, s: OnlineService, r: Request): Map<String, Any?> {
        val box = r.query["bbox"]?.let { raw ->
            val v = raw.split(',').map { it.trim().toDoubleOrNull() ?: throw IllegalArgumentException("bbox must be west,south,east,north") }
            require(v.size == 4) { "bbox must be west,south,east,north" }
            PackBounds(v[0], v[1], v[2], v[3])
        } ?: OnlineView.current(app)?.bounds ?: throw IllegalStateException("map not ready; pass bbox=")
        val zooms = r.query["zooms"]?.let { z ->
            val parts = z.split('-', ',').map { it.trim().toIntOrNull() ?: throw IllegalArgumentException("zooms must be like 8-13") }
            require(parts.size == 2 && parts[0] <= parts[1]) { "zooms must be like 8-13" }
            parts[0] to parts[1]
        }
        return s.region.start(box, zooms?.first, zooms?.second, r.query["name"]?.takeIf { it.isNotBlank() }, r.query["profile"]) // T64: profile=outdoors (default) or openmaptiles
    }

    /**
     * Applies a `server` setting (from `POST /settings`): "" turns online mode off. Reopens the tiles and
     * reloads the style. Returns an error message for an invalid value, else null.
     */
    fun setServer(app: DystopoApp, value: String): String? {
        val v = AppSettings.normalizeServer(value) ?: return "server must be empty or http(s)://host[:port][/path] (got '$value')"
        AppSettings.setServer(v)
        OnlineService.get(app).configure(v)
        app.reload()
        return null
    }
}
