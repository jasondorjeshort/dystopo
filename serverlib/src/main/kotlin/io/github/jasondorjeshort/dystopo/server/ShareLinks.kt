package io.github.jasondorjeshort.dystopo.server

import com.sun.net.httpserver.HttpExchange
import io.github.jasondorjeshort.dystopo.maplibre.JsonWriter
import io.github.jasondorjeshort.dystopo.model.RouteGpx
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.model.RouteSummary
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

/**
 * T67: read-only share links for trips (routes). A link is a random token (128 bits, base64url) that
 * names one route in one user's replica; anyone with the link can see the route, nobody can change
 * anything through it. Links live in `shares.json` next to the replicas (`out/sync/shares.json`), not in
 * the sync log: they are the server's, not the user's data, and revoking one must not need a sync.
 *
 * The link serves the route **as it is now** in the owner's replica, so an edit synced later shows up
 * and a route deleted (and synced) is a 404, like a revoked link. Routes (registered by [SyncRoutes]):
 * - `POST /sync/share?route=<id>` (token-scoped): creates the link, or returns the existing one for that
 *   route (`existing: true`); 404 when the replica does not hold the route (sync first).
 * - `GET /sync/share`: this user's links. `DELETE /sync/share/<token>`: revokes one (404 if it is not
 *   this user's).
 * - `GET /s/<token>`: a small page with the route on the live map (MapLibre GL JS over `/style.json`),
 *   name, distance, climb, time and a GPX download; `GET /s/<token>.gpx`: the GPX (the same bytes as the
 *   phone's `GET /routes/{id}.gpx`); `GET /s/<token>.json`: name, summary and the route as GeoJSON.
 *   Open (no token): the link is the secret. `Cache-Control: no-store` (a revocation is immediate),
 *   `Referrer-Policy: no-referrer` (the token is in the URL) and `X-Robots-Tag: noindex`.
 */
class ShareLinks(val file: File) {
    data class Link(val token: String, val user: String, val route: String, val createdAt: Long) {
        fun json(base: String?): Map<String, Any?> = linkedMapOf(
            "token" to token, "route" to route, "createdAt" to createdAt,
            "url" to "${base.orEmpty()}/s/$token", "gpx" to "${base.orEmpty()}/s/$token.gpx",
        )
    }

    private val links = LinkedHashMap<String, Link>()
    private var loaded = false

    @Volatile var created = 0L
        private set
    @Volatile var revoked = 0L
        private set
    @Volatile var served = 0L
        private set

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        val o = MiniJson.parseObject(file.readText())
        for (l in (o["links"] as? List<*>).orEmpty()) {
            val m = l as? Map<*, *> ?: continue
            val t = m["token"] as? String ?: continue
            links[t] = Link(t, m["user"] as String, m["route"] as String, (m["createdAt"] as? Number)?.toLong() ?: 0L)
        }
    }

    @Synchronized
    private fun save() {
        file.absoluteFile.parentFile.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JsonWriter.write(mapOf("version" to 1, "links" to links.values.map { linkedMapOf("token" to it.token, "user" to it.user, "route" to it.route, "createdAt" to it.createdAt) })) + "\n")
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "cannot write ${file.path}" }
        }
    }

    /** The link for [user]'s [route]: the existing one (second = true) or a new one. */
    @Synchronized
    fun create(user: String, route: String, now: Long): Pair<Link, Boolean> {
        load()
        links.values.firstOrNull { it.user == user && it.route == route }?.let { return it to true }
        val link = Link(newToken(), user, route, now)
        links[link.token] = link
        save()
        created++
        return link to false
    }

    @Synchronized
    fun get(token: String): Link? { load(); return links[token] }

    @Synchronized
    fun list(user: String): List<Link> { load(); return links.values.filter { it.user == user } }

    @Synchronized
    fun count(): Int { load(); return links.size }

    /** Revokes [token] when it belongs to [user]; false (a 404 to the caller) otherwise. */
    @Synchronized
    fun revoke(token: String, user: String): Boolean {
        load()
        val l = links[token]?.takeIf { it.user == user } ?: return false
        links.remove(l.token)
        save()
        revoked++
        return true
    }

    /** Revokes every link of [user] (a removed user); returns how many. */
    @Synchronized
    fun revokeAll(user: String): Int {
        load()
        val gone = links.values.filter { it.user == user }
        if (gone.isEmpty()) return 0
        gone.forEach { links.remove(it.token) }
        save()
        revoked += gone.size
        return gone.size
    }

    fun stats(): Map<String, Any?> = linkedMapOf("links" to count(), "created" to created, "revoked" to revoked, "served" to served)

    /**
     * `GET /s/<token>[.gpx|.json]`: answers and returns true, or false for another path. [routeOf] reads the
     * route from the owner's replica (null: the route is gone).
     */
    fun serve(ex: HttpExchange, routeOf: (user: String, route: String) -> RoutePlan?): Boolean {
        val path = ex.requestURI.path
        if (!path.startsWith("/s/")) return false
        val m = PATH.matchEntire(path)
        if (ex.requestMethod != "GET" && ex.requestMethod != "HEAD") return reply(ex, 405, "text/plain; charset=utf-8", "GET only\n".toByteArray())
        val link = m?.let { get(it.groupValues[1]) }
        val plan = link?.let { routeOf(it.user, it.route) }
        if (link == null || plan == null) return reply(ex, 404, "text/html; charset=utf-8", NOT_FOUND.toByteArray())
        served++
        return when (m.groupValues[2]) {
            ".gpx" -> {
                ex.responseHeaders.add("Content-Disposition", "attachment; filename=\"" + fileName(plan.name) + ".gpx\"")
                reply(ex, 200, "application/gpx+xml; charset=utf-8", RouteGpx.gpx(plan).toByteArray())
            }
            ".json" -> reply(ex, 200, "application/json", (JsonWriter.write(routeJson(plan)) + "\n").toByteArray())
            else -> reply(ex, 200, "text/html; charset=utf-8", page(link.token, plan).toByteArray())
        }
    }

    private fun reply(ex: HttpExchange, status: Int, type: String, bytes: ByteArray): Boolean {
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.responseHeaders.add("Referrer-Policy", "no-referrer")
        ex.responseHeaders.add("X-Robots-Tag", "noindex")
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        if (ex.requestMethod == "HEAD") {
            ex.sendResponseHeaders(status, -1)
            ex.close()
            return true
        }
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        return true
    }

    companion object {
        private val PATH = Regex("^/s/([A-Za-z0-9_-]{16,64})(\\.gpx|\\.json)?$")
        private val random = SecureRandom()

        /** 16 random bytes as base64url without padding (22 characters). */
        fun newToken(): String = ByteArray(16).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        fun fileName(name: String): String = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(80).ifEmpty { "route" }

        /** Name, summary numbers, via points, waypoints and the legs as a GeoJSON FeatureCollection (one LineString per leg, `kind` trail/straight/fixed). */
        fun routeJson(plan: RoutePlan): Map<String, Any?> {
            val s = RouteSummary.of(plan)
            val pts = plan.path.points
            val features = ArrayList<Map<String, Any?>>()
            for (leg in plan.path.legs) {
                if (leg.points.size < 2) continue
                features += linkedMapOf(
                    "type" to "Feature", "properties" to mapOf("kind" to leg.kind.label),
                    "geometry" to mapOf("type" to "LineString", "coordinates" to leg.points.map { listOf(it.lon, it.lat) }),
                )
            }
            for (w in plan.waypoints) {
                features += linkedMapOf(
                    "type" to "Feature", "properties" to mapOf("kind" to "waypoint", "name" to w.name),
                    "geometry" to mapOf("type" to "Point", "coordinates" to listOf(w.lon, w.lat)),
                )
            }
            if (pts.isNotEmpty()) {
                features += linkedMapOf("type" to "Feature", "properties" to mapOf("kind" to "start"), "geometry" to mapOf("type" to "Point", "coordinates" to listOf(pts.first().lon, pts.first().lat)))
                features += linkedMapOf("type" to "Feature", "properties" to mapOf("kind" to "end"), "geometry" to mapOf("type" to "Point", "coordinates" to listOf(pts.last().lon, pts.last().lat)))
            }
            val bounds = if (pts.isEmpty()) null else listOf(pts.minOf { it.lon }, pts.minOf { it.lat }, pts.maxOf { it.lon }, pts.maxOf { it.lat })
            return linkedMapOf(
                "name" to plan.name, "description" to plan.description, "bounds" to bounds,
                "summary" to s.json(profileSamples = false),
                "waypoints" to plan.waypoints.map { linkedMapOf("name" to it.name, "lat" to it.lat, "lon" to it.lon, "ele" to it.ele) },
                "geojson" to mapOf("type" to "FeatureCollection", "features" to features),
            )
        }

        private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

        private const val NOT_FOUND = "<!doctype html><meta charset=\"utf-8\"><title>Not found</title>" +
            "<p style=\"font-family:sans-serif\">This trip link does not exist or was revoked.</p>\n"

        /**
         * The share page: the route over the server's own map style, fitted to the route, with the numbers
         * and a GPX link. MapLibre GL JS comes from unpkg (the same version as tools/demo/index.html). The
         * page exposes `window.map` and sets `window.dystopoShare = {ready, routeFeatures}` once the route
         * layer has rendered, for headless checks.
         */
        fun page(token: String, plan: RoutePlan): String {
            val s = RouteSummary.of(plan)
            val km = String.format(Locale.ROOT, "%.1f km", s.distanceM / 1000)
            val mi = String.format(Locale.ROOT, "%.1f mi", s.distanceM / 1609.344)
            val up = s.ascentM?.let { " · +${Math.round(it)} m / +${Math.round(it / 0.3048)} ft" } ?: ""
            val name = esc(plan.name.ifBlank { "Route" })
            return """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<meta name="robots" content="noindex">
<link rel="icon" href="data:,">
<title>$name</title>
<script src="https://unpkg.com/maplibre-gl@5.24.0/dist/maplibre-gl.js"></script>
<link href="https://unpkg.com/maplibre-gl@5.24.0/dist/maplibre-gl.css" rel="stylesheet">
<style>
html,body{margin:0;height:100%;font-family:system-ui,sans-serif;background:#f5f2ea;color:#2b2420}
#map{position:absolute;inset:0}
#card{position:absolute;left:12px;top:12px;max-width:min(360px,calc(100% - 24px));background:rgba(255,252,245,.95);border-radius:10px;padding:10px 14px;box-shadow:0 1px 6px rgba(0,0,0,.25)}
#card h1{font-size:18px;margin:0 0 4px;color:#7b3294}
#card p{margin:2px 0;font-size:14px}
#card a{color:#2c7bb6;font-weight:bold}
</style></head><body>
<div id="map"></div>
<div id="card"><h1>$name</h1>
<p>$km ($mi)$up · ${esc(RouteSummary.hm(s.timeS))} ${esc(s.mode)}</p>
<p><a id="gpx" href="$token.gpx" download>Download GPX</a> · shared from Dystopo</p></div>
<script>
window.dystopoShare = {ready: false, routeFeatures: 0, error: null};
fetch('$token.json').then(r => r.json()).then(route => {
  const map = new maplibregl.Map({container: 'map', style: '/style.json', bounds: route.bounds, fitBoundsOptions: {padding: {top: 120, bottom: 60, left: 40, right: 40}}, attributionControl: {compact: true}});
  window.map = map;
  map.addControl(new maplibregl.NavigationControl(), 'bottom-right');
  map.on('load', () => {
    // The compact credit starts expanded on a wide window and would cover the route: start it collapsed.
    document.querySelectorAll('.maplibregl-ctrl-attrib').forEach(e => e.classList.remove('maplibregl-compact-show'));
    map.addSource('share-route', {type: 'geojson', data: route.geojson});
    map.addLayer({id: 'share-route-casing', type: 'line', source: 'share-route', filter: ['==', ['geometry-type'], 'LineString'],
      layout: {'line-cap': 'round', 'line-join': 'round'}, paint: {'line-color': '#ffffff', 'line-width': 7}});
    map.addLayer({id: 'share-route', type: 'line', source: 'share-route', filter: ['==', ['geometry-type'], 'LineString'],
      layout: {'line-cap': 'round', 'line-join': 'round'},
      paint: {'line-color': '#7b3294', 'line-width': 4, 'line-dasharray': ['case', ['==', ['get', 'kind'], 'straight'], ['literal', [2, 1.5]], ['literal', [1, 0]]]}});
    map.addLayer({id: 'share-points', type: 'circle', source: 'share-route', filter: ['==', ['geometry-type'], 'Point'],
      paint: {'circle-radius': 6, 'circle-stroke-width': 2, 'circle-stroke-color': '#ffffff',
        'circle-color': ['match', ['get', 'kind'], 'start', '#1a9641', 'end', '#d7191c', '#c79a00']}});
    map.once('idle', () => {
      window.dystopoShare = {ready: true, routeFeatures: map.queryRenderedFeatures({layers: ['share-route']}).length, error: null};
    });
  });
}).catch(e => { window.dystopoShare.error = String(e); });
</script></body></html>
"""
        }
    }
}
