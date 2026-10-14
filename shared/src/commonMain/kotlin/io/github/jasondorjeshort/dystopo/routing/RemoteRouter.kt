package io.github.jasondorjeshort.dystopo.routing

import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Route
import io.github.jasondorjeshort.dystopo.model.RoutePoint
import io.github.jasondorjeshort.dystopo.model.TrackPoint
import io.github.jasondorjeshort.dystopo.model.UuidGenerator
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import kotlin.math.roundToLong

/**
 * Plans on a Dystopo server (T77, PLANNING › Routing): `GET <server>/route?from=&to=&mode=&snap=`,
 * the route the browser's trip editor gets, planned over every pack the server holds near the route
 * (with the [DriveOverlay] for long drives). The phone keeps the answer as geometry, a trip like any
 * other; downloading the trip's corridor (T75 `POST /areas/download?trip=`) is what makes it work
 * offline, so the phone never needs a graph of the whole way.
 *
 * [fetch] does the HTTP GET (the platform's client; on the phone through the cleartext policy) and
 * returns the status and body; it may throw on a network error. [server] is the base URL.
 */
class RemoteRouter(
    val server: String,
    private val fetch: (url: String) -> Pair<Int, String>,
    private val clock: () -> Long = { 0L },
) : Router {
    /** The last request: URL, status or error, milliseconds (for status pages). */
    var last: Map<String, Any?>? = null
        private set
    var requests = 0L
        private set

    override fun route(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
        mode: TravelMode, maxSnapM: Double, name: String, ids: UuidGenerator,
    ): RouteResult {
        val base = server.trimEnd('/')
        val snap = maxSnapM.coerceIn(10.0, 2000.0).roundToLong()
        val url = "$base/route?from=${c(fromLat)},${c(fromLon)}&to=${c(toLat)},${c(toLon)}&mode=${mode.label}&snap=$snap"
        val t0 = clock()
        requests++
        val (status, body) = try {
            fetch(url)
        } catch (e: Exception) {
            last = linkedMapOf("url" to url, "error" to (e.message ?: e.toString()), "ms" to clock() - t0)
            return unavailable("the server $base did not answer (${e.message ?: e::class.simpleName})")
        }
        val ms = clock() - t0
        last = linkedMapOf("url" to url, "status" to status, "ms" to ms)
        val json = runCatching { MiniJson.parseObject(body) }.getOrNull()
        if (status != 200 || json == null) {
            val why = (json?.get("error") as? String) ?: "HTTP $status"
            return unavailable("the server $base could not plan the route ($why)")
        }
        return parse(json, mode, name, ids, fromLat, fromLon, toLat, toLon, ms)
    }

    /** No `nearest` on the server: snapping happens there, inside [route]. */
    override fun nearest(lat: Double, lon: Double, maxDistanceM: Double): EdgeSnap? = null

    private fun unavailable(message: String) = RouteResult.Failed(RouteFailure.REMOTE_UNAVAILABLE, message, linkedMapOf("server" to server))

    @Suppress("UNCHECKED_CAST")
    internal fun parse(
        json: Map<String, Any?>, mode: TravelMode, name: String, ids: UuidGenerator,
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, ms: Long,
    ): RouteResult {
        val legs = json["legs"] as? List<Map<String, Any?>> ?: emptyList()
        val notes = (json["notes"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        val messages = json["messages"] as? Map<String, Any?> ?: emptyMap()
        if (legs.isEmpty() || legs.any { it["kind"] != "trail" } || notes.isNotEmpty()) {
            val code = notes.firstOrNull() ?: RouteFailure.NOT_CONNECTED.code
            val reason = RouteFailure.entries.firstOrNull { it.code == code } ?: RouteFailure.NOT_CONNECTED
            val msg = messages[code] as? String ?: "the server found no route ($code)"
            return RouteResult.Failed(reason, "$msg (planned on $server)", linkedMapOf("server" to server, "remote" to true))
        }
        val pts = ArrayList<RoutePoint>()
        for (leg in legs) for (c in leg["coordinates"] as? List<List<Any?>> ?: emptyList()) {
            val lon = (c.getOrNull(0) as? Number)?.toDouble() ?: continue
            val lat = (c.getOrNull(1) as? Number)?.toDouble() ?: continue
            val p = RoutePoint(lat, lon, (c.getOrNull(2) as? Number)?.toDouble())
            if (pts.lastOrNull()?.let { it.lat == p.lat && it.lon == p.lon } == true) continue
            pts += p
        }
        if (pts.size < 2) return RouteResult.Failed(RouteFailure.NOT_CONNECTED, "the server's route has no points", linkedMapOf("server" to server))
        val track = listOf(pts.map { TrackPoint(it.lat, it.lon, it.ele) })
        val stats = TrackStats.of(track)
        val summary = json["summary"] as? Map<String, Any?> ?: emptyMap()
        val hasEle = pts.any { it.ele != null }
        val ascent = (summary["ascentM"] as? Number)?.toDouble() ?: if (hasEle) stats.ascentM else null
        val descent = (summary["descentM"] as? Number)?.toDouble() ?: if (hasEle) stats.descentM else null
        // T89: the server's summary time (for a drive, the sum of its router's per-road times), else the model's.
        val time = (summary["timeS"] as? Number)?.toDouble() ?: CostModel(mode).timeS(stats.distanceM, ascent ?: 0.0)
        val host = server.substringAfter("://").trimEnd('/')
        val desc = "${Router.km(stats.distanceM)} km" + (if (ascent != null) ", +${ascent.roundToLong()} m / -${(descent ?: 0.0).roundToLong()} m" else "") +
            ", about ${PlannedRoute.hm(time)} (${mode.label}; planned by the Dystopo server at $host" +
            (if (mode == TravelMode.DRIVE) " on its packs' roads, not a road navigator" else "") + "; download its corridor to follow it offline)"
        val route = Route(ids.next(), name, pts, description = desc, type = mode.label)
        fun end(lat: Double, lon: Double, p: RoutePoint) = EdgeSnap(-1, 0, 0.0, p.lat, p.lon, TrackStats.distanceM(lat, lon, p.lat, p.lon))
        return RouteResult.Found(
            PlannedRoute(
                route, mode, stats.distanceM, ascent, descent, time, TrackStats.profile(track, Router.PROFILE_SAMPLES),
                cost = 0.0, edges = 0, start = end(fromLat, fromLon, pts.first()), end = end(toLat, toLon, pts.last()), expanded = 0,
                metersByClass = emptyMap(), via = emptyList(),
                memory = linkedMapOf("remote" to server, "remoteMs" to ms, "serverMs" to json["ms"], "pack" to json["pack"]),
            ),
        )
    }

    private fun c(v: Double) = ((v * 1e6).roundToLong() / 1e6).toString()
}

/**
 * The phone's router (T77): [local] (the packs on the device) first; when it fails because an end lies
 * where no local pack reaches ([covers] is false for it) and a server is set, [remote] plans it, so a
 * drive to a town beyond the packs still has a route. When the server cannot help either, the local
 * failure stands (its message names the nearest trail). Ends inside the local packs go remote only for
 * a long drive (T89): two packs on the phone that do not join (a home pack and a destination pack) or a
 * search that hit its limit, when the ends are [DriveOverlay.MIN_DISTANCE_M] or more apart; otherwise an
 * off-network tap there is the user's to fix, not the server's.
 */
class FallbackRouter(
    val local: Router?,
    val remote: Router,
    private val covers: (lat: Double, lon: Double) -> Boolean,
) : Router {
    /** Whether the last [route] went to [remote], and what it answered (for status pages). */
    var lastRemote: Map<String, Any?>? = null
        private set

    override fun route(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
        mode: TravelMode, maxSnapM: Double, name: String, ids: UuidGenerator,
    ): RouteResult {
        val l = local?.route(fromLat, fromLon, toLat, toLon, mode, maxSnapM, name, ids)
        if (l is RouteResult.Found) return l
        val fallBack = l == null || (l as RouteResult.Failed).reason in REMOTE_REASONS && (
            !(covers(fromLat, fromLon) && covers(toLat, toLon)) ||
                // T89: both ends on the phone's packs, but they do not join (or the search ran out of budget) on a long drive.
                mode == TravelMode.DRIVE && l.reason in LONG_DRIVE_REASONS &&
                TrackStats.distanceM(fromLat, fromLon, toLat, toLon) >= DriveOverlay.MIN_DISTANCE_M
            )
        if (!fallBack) return l!!
        val r = remote.route(fromLat, fromLon, toLat, toLon, mode, maxSnapM, name, ids)
        lastRemote = linkedMapOf("found" to (r is RouteResult.Found), "error" to (r as? RouteResult.Failed)?.reason?.code)
        return if (r is RouteResult.Found || l == null) r else l
    }

    override fun nearest(lat: Double, lon: Double, maxDistanceM: Double): EdgeSnap? = local?.nearest(lat, lon, maxDistanceM)
    override fun nearest(lat: Double, lon: Double, maxDistanceM: Double, mode: TravelMode): EdgeSnap? = local?.nearest(lat, lon, maxDistanceM, mode)

    companion object {
        val REMOTE_REASONS = setOf(RouteFailure.START_OFF_NETWORK, RouteFailure.END_OFF_NETWORK, RouteFailure.NOT_CONNECTED, RouteFailure.EMPTY_GRAPH, RouteFailure.SEARCH_LIMIT)

        /** T89: failures that send a long drive between two covered ends to the server. */
        val LONG_DRIVE_REASONS = setOf(RouteFailure.NOT_CONNECTED, RouteFailure.SEARCH_LIMIT)
    }
}
