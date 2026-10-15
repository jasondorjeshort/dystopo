package io.github.jasondorjeshort.dystopo.app.areas

import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.ContourCache
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.trips.Trips
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.AreaPoint
import io.github.jasondorjeshort.dystopo.areas.AreaShape
import io.github.jasondorjeshort.dystopo.areas.Polyline
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson

/**
 * Debug routes for areas of interest (T75; registered with one line in `Routes`):
 * - `GET /areas`: every area (kind, pinned, state, bytes, estimate, coverage now: tiles present out of the
 *   manifest's, per route), the cache, the running download and the prefetch state. `GET /areas/{id}`: one;
 *   `GET /areas/{id}/manifest`: its manifest with the tile lists.
 * - `POST /areas/download`: downloads an area. The area as a JSON body (`{"kind": "corridor", "line": [[lon, lat], ...],
 *   "bufferM": 500, "minZoom": 8, "maxZoom": 15}`, see [AreaOfInterest]) or query parameters (`bbox=w,s,e,n`,
 *   `line=lat,lon;lat,lon`, or `trip=<trip id or prefix>` for a kept route's corridor; with `bufferM`, `minZoom`,
 *   `maxZoom`, `name`, `contours=1`). A box is "Download this region" (a pack); a corridor is fetched into the tile
 *   cache and pinned. `GET /areas/download`: progress; `DELETE /areas/download`: cancel.
 * - T95: `POST /areas/plan` with the same parameters: the corridor's manifest size, tiles per route, and whether the
 *   pinned budget allows it (`fits`, `refusal`), with nothing started (what a trip's Offline button shows).
 *   `GET /areas` and `GET /areas/{id}` give each area's `versions` (per source, what its tiles were fetched at),
 *   `olderThanSource` (the content names whose version the source has moved on from; empty when current, null when
 *   unknown) and `coverage.atOlderVersion` (tiles found only at the area's own older version). Downloading an area
 *   again is its Update.
 * - `POST /areas/{id}/pin?on=0|1`: pin or unpin (T91: 409 for a pack-backed area, which is never evicted); `DELETE /areas/{id}`: remove (a box's pack is deleted, a corridor's
 *   tiles leave the cache).
 * - T106: `POST /areas/{id}/retry` is the card's Retry (the corridor downloaded again, as Download again; 409 for a
 *   pack-backed area); `POST /areas/{id}/dismiss` its Dismiss on a failed, partial or refused download: the message
 *   goes, the area keeps what it has (`done` when whole, else `partial`) or is removed when nothing of it is on the
 *   phone (`removed` true). `GET /areas` also has `fromOtherSource` per area (fetched from a source the phone no
 *   longer reads: then `olderThanSource` names what it holds, and Update fetches it from the source set now and
 *   deletes the old source's tiles in its cover) and `pinnedBySource` (pinned tiles and bytes per source cache key,
 *   `inUse` when the phone reads that source now).
 * - `POST /areas/trim?mb=N`: sets the online cache's cap to N MB now (not saved; `restore=1` goes back to the
 *   setting) and evicts unpinned tiles down to it; answers the cache before and after and every area's coverage,
 *   which is how "pinned areas survive a trim" is checked.
 * - `GET /areas/prefetch`, `POST /areas/prefetch?on=0|1`: "ahead on the route" while navigating ([AheadPrefetch]).
 * - `POST /areas/ui?open=1|0`: the Areas screen.
 */
object AreaRoutes {
    private val item = Regex("^/areas/(a[0-9a-f]{4,16})(/manifest|/pin|/retry|/dismiss)?$")

    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/areas" && !r.path.startsWith("/areas/")) return null
        return try {
            route(app, r)
        } catch (e: NoSuchElementException) {
            Response.json(mapOf("error" to e.message), 404)
        } catch (e: IllegalArgumentException) {
            Response.json(mapOf("error" to e.message), 400)
        } catch (e: IllegalStateException) {
            Response.json(mapOf("error" to e.message), 409)
        }
    }

    private fun route(app: DystopoApp, r: Request): Response {
        when (r.method to r.path) {
            "GET" to "/areas" -> return Response.json(Areas.json())
            "GET" to "/areas/download" -> return Response.json(AreaDownload.json() ?: mapOf("state" to "idle"))
            "DELETE" to "/areas/download", "POST" to "/areas/download/cancel" -> return Response.json(mapOf("cancelled" to AreaDownload.cancel(), "download" to AreaDownload.json()))
            "POST" to "/areas/download" -> return Response.json(download(r), 202)
            "POST" to "/areas/plan" -> return Response.json(AreaDownload.plan(area(r).second).json())
            "POST" to "/areas/trim" -> return Response.json(trim(r))
            "GET" to "/areas/prefetch" -> return Response.json(AheadPrefetch.json())
            "POST" to "/areas/prefetch" -> {
                r.query["on"]?.let { AheadPrefetch.enabled = it == "1" || it == "true" }
                return Response.json(AheadPrefetch.json())
            }
            "POST" to "/areas/ui" -> {
                val open = r.query["open"] != "0"
                app.map.onMain { Areas.screenOpen = open }
                return Response.json(mapOf("open" to open))
            }
        }
        val m = item.matchEntire(r.path) ?: return Response.notFound()
        val id = m.groupValues[1]
        val rec = Areas.get(id) ?: throw NoSuchElementException("no area '$id'")
        return when (r.method to m.groupValues[2]) {
            "GET" to "" -> Response.json(rec.json() + mapOf("olderThanSource" to Areas.older(rec), "coverage" to Areas.coverage(rec), "manifest" to Areas.manifest(rec.id)?.summary()))
            "GET" to "/manifest" -> Response.json(Areas.manifest(rec.id)?.json() ?: throw NoSuchElementException("area $id has no manifest (a pack-backed area: see its pack)"))
            "POST" to "/pin" -> Response.json(Areas.pin(rec.id, r.query["on"] != "0").json())
            "POST" to "/retry" -> Response.json(retry(rec), 202) // T106: the card's Retry (a download again)
            "POST" to "/dismiss" -> Response.json(mapOf("area" to Areas.dismiss(rec.id)?.json(), "removed" to (Areas.get(rec.id) == null))) // T106
            "DELETE" to "" -> Response.json(Areas.remove(rec.id))
            else -> Response.notFound()
        }
    }

    /** T106: Retry on an area card: the same corridor downloaded again (a pack-backed area is a region pack: 409). */
    private fun retry(rec: Areas.Record): Map<String, Any?> {
        check(rec.pack == null) { "area ${rec.name} is the pack ${rec.pack}: download a region again from the pack list" }
        return AreaDownload.start(rec.area, rec.name, rec.tripId)
    }

    private fun download(r: Request): Map<String, Any?> {
        val (tripId, area) = area(r)
        return AreaDownload.start(area, r.query["name"], tripId, contours = r.query["contours"] == "1")
    }

    /** The area a request names (JSON body, `trip=`, or query parameters) and the trip it is for. */
    private fun area(r: Request): Pair<String?, AreaOfInterest> {
        val q = r.query
        val body = r.bodyText.trim()
        return when {
            body.startsWith("{") -> {
                val doc = MiniJson.parseObject(body)
                @Suppress("UNCHECKED_CAST")
                null to AreaOfInterest.parse((doc["area"] as? Map<String, Any?>) ?: doc)
            }
            q["trip"] != null -> tripArea(q["trip"]!!, q)
            else -> null to AreaOfInterest.fromQuery(q)
        }
    }

    /**
     * The corridor of kept route [key] (id or prefix): its line, `bufferM` (default 500 m), zooms 8 to 15, or (T91)
     * 8 to 13 for a drive ([corridorMaxZoom]); `minZoom`/`maxZoom` override.
     */
    fun tripArea(key: String, q: Map<String, String> = emptyMap()): Pair<String, AreaOfInterest> {
        val plans = Trips.store { it.routePlans() }
        val p = plans.firstOrNull { it.id == key } ?: plans.filter { key.length >= 4 && it.id.startsWith(key) }.singleOrNull()
            ?: throw NoSuchElementException("no kept route '$key' (GET /routes)")
        val pts = p.path.points.map { AreaPoint(it.lat, it.lon) }
        require(pts.isNotEmpty()) { "route ${p.id} has no points" }
        val buffer = q["bufferM"]?.let { it.toDoubleOrNull() ?: throw IllegalArgumentException("bufferM must be a number") } ?: AreaOfInterest.DEFAULT_BUFFER_M
        // Simplified within a tenth of the buffer, and the buffer grown by as much, so the corridor still holds the line's.
        val tol = buffer / 10
        val line = Polyline.simplify(pts, tol)
        val area = AreaOfInterest(
            AreaShape.Corridor(line, buffer + tol),
            q["minZoom"]?.toIntOrNull() ?: 8, q["maxZoom"]?.toIntOrNull() ?: corridorMaxZoom(p.type), q["name"] ?: p.name.ifBlank { "Route" },
        )
        return p.id to area
    }

    private fun trim(r: Request): Map<String, Any?> {
        val cache = OnlineService.get(DystopoApp.instance).cache
        val before = cache.json()
        val beforeAreas = Areas.all().associate { it.id to Areas.coverage(it)["share"] }
        val mb = if (r.query["restore"] == "1") AppSettings.onlineCacheMb else (r.query["mb"]?.toLongOrNull() ?: throw IllegalArgumentException("mb=<megabytes> or restore=1"))
        require(mb in 0..65_536) { "mb must be 0 to 65536" }
        cache.resize(mb * ContourCache.MB)
        return linkedMapOf(
            "capMb" to mb, "saved" to false, "before" to before, "after" to cache.json(),
            "evicted" to ((cache.json()["evictions"] as Long) - (before["evictions"] as Long)),
            "areas" to Areas.all().map { a -> linkedMapOf("id" to a.id, "name" to a.name, "pinned" to a.pinned, "coverageBefore" to beforeAreas[a.id], "coverage" to Areas.coverage(a)) },
        )
    }
}

/**
 * T91 (the T77 follow-up): the deepest zoom a trip's corridor fetches by default. A drive is seen from a car at
 * z13 at most (z8 to z15 was 134 MB for an 84 km drive), a walk or ride wants the trail detail of z15.
 */
internal fun corridorMaxZoom(type: String?): Int = if (type.equals("drive", ignoreCase = true)) 13 else 15
