package io.github.jasondorjeshort.dystopo.app.areas

import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.ContourCache
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.trips.Trips
import io.github.jasondorjeshort.dystopo.areas.AreaOfInterest
import io.github.jasondorjeshort.dystopo.areas.TripCorridor
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
 * - T130, the corridor every trip keeps ([TripCorridors]): `GET /areas/trips` (the width setting `widthM`, `notKept`
 *   trips, `notes` (why a trip has none: over the budget, no source), `queued`, `refusals`, `lastRun` and every trip's
 *   `corridors` with their width); `POST /areas/trips[?wait=ms]` runs a reconcile (answers after it with `wait`). A
 *   trip's area carries its `tripId`; `POST /areas/{id}/pin?on=0` and `DELETE /areas/{id}` on one also stop keeping that
 *   trip (not made again until Keep offline, `POST /routes/{id}/corridor?keep=1`, or Pin). The width is
 *   `POST /settings?tripCorridorM=` (0 off, 100 to 10000 m; 1000 by default), also the default `bufferM` of `?trip=`.
 * - T136: the width syncs (`app.tripCorridorM`); raising `pinnedCacheMb` retries a refused corridor; a trip edited during its
 *   corridor's download cancels it (`lastRun.cancelled`), and `GET /areas/download` keeps the ended downloads in `recent`
 *   (newest first, five); `GET /areas` `outline.corridors` is every kept corridor, with `drawn` while the sheet shows it.
 * - T140 (ADR 0041): polygon areas. `POST /areas/plan` and `POST /areas/download` take a polygon body
 *   (`{"kind": "polygon", "rings": [[[lon, lat], ...], ...], "bufferM": 0, "minZoom": 8, "maxZoom": 15, "name": ...}`, rings
 *   even-odd, holes and parts as more rings) or `ring=lat,lon;lat,lon;...` (more rings after `|`), and the area around a
 *   point as `around=lat,lon&radiusM=` (the one-point corridor up to 20 km, a 48-vertex polygon circle beyond). The plan of
 *   a drawn area or a disc is made on the phone and adds `shape` (box, corridor, disc, polygon), `manifestVersion`,
 *   `sizeLine` ("About 170 MB (1,300 tiles) at full detail"), `timeLine` and `seconds` (at the last completed download's
 *   speed; null before the first), `lessDetail` (z8 to z13: `zooms`, `bytes`, `tiles`, `line` "Less detail (about 30 MB)",
 *   `fits`, `refusal`) with `offerLessDetail` (when the full plan is refused), and `tooLarge` with the refusal "This area is
 *   too large at this detail; make it smaller or choose less detail" when the heap's tile limit refuses it (nothing filled).
 *   Over the budget its `refusal` is "This area is about 450 MB; 1.6 GB of your 2 GB offline budget is in use. Make the
 *   area smaller, choose less detail, or raise the budget in Settings › Storage." `GET /areas` gives each area
 *   `needsNewerApp` (a record of a version or kind this build cannot read, kept as it was, pinned by its `bounds` and
 *   `zooms`; `needsNewerAppReason` the raw sentence; Pin, Retry and Dismiss answer 409), a polygon's `rings`, `vertices`
 *   and `areaKm2`, a disc's `radiusM`, and `files` (records in `areas.json` and in the sibling `areas2.json`).
 *   `GET /areas/download` `recent` no longer repeats the job shown above it; `GET /areas/trips` `lastRun` is the last run
 *   that made, refused, cancelled or released a corridor (a run that did nothing leaves it).
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
            "GET" to "/areas/trips" -> return Response.json(TripCorridors.json()) // T130
            "POST" to "/areas/trips" -> { // T130: a reconcile now (`wait=ms` answers after it)
                TripCorridors.request("debug route")
                r.query["wait"]?.toLongOrNull()?.let { TripCorridors.drain(it.coerceIn(0, 600_000)) }
                return Response.json(TripCorridors.json())
            }
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
            "POST" to "/pin" -> Response.json(TripCorridors.userPin(rec.id, r.query["on"] != "0").json()) // T130: on a trip's corridor, also Keep offline or Stop keeping
            "POST" to "/retry" -> Response.json(retry(rec), 202) // T106: the card's Retry (a download again)
            "POST" to "/dismiss" -> Response.json(mapOf("area" to Areas.dismiss(rec.id)?.json(), "removed" to (Areas.get(rec.id) == null))) // T106
            "DELETE" to "" -> Response.json(TripCorridors.userRemove(rec.id)) // T130: a trip's corridor removed by hand is not made again
            else -> Response.notFound()
        }
    }

    /** T106: Retry on an area card: the same corridor downloaded again (a pack-backed area is a region pack: 409). */
    private fun retry(rec: Areas.Record): Map<String, Any?> {
        check(rec.pack == null) { "area ${rec.name} is the pack ${rec.pack}: download a region again from the pack list" }
        check(rec.opaque == null) { "area ${rec.name}: ${io.github.jasondorjeshort.dystopo.areas.NeedsNewerApp.USER_SENTENCE}" } // T140
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
     * The corridor of kept route [key] (id or prefix): its line, `bufferM` (T130: default the trip corridor setting,
     * [TripCorridors.widthM], 1 km unless changed; 500 m before), zooms 8 to 15, or (T91) 8 to 13 for a drive
     * ([corridorMaxZoom]); `minZoom`/`maxZoom` override. The shape is [TripCorridor.area], the same as the automatic corridor's.
     */
    fun tripArea(key: String, q: Map<String, String> = emptyMap()): Pair<String, AreaOfInterest> {
        val plans = Trips.store { it.routePlans() }
        val p = plans.firstOrNull { it.id == key } ?: plans.filter { key.length >= 4 && it.id.startsWith(key) }.singleOrNull()
            ?: throw NoSuchElementException("no kept route '$key' (GET /routes)")
        val buffer = q["bufferM"]?.let { it.toDoubleOrNull() ?: throw IllegalArgumentException("bufferM must be a number") } ?: TripCorridors.widthM()
        return p.id to TripCorridors.areaOf(p, buffer, q["minZoom"]?.toIntOrNull(), q["maxZoom"]?.toIntOrNull(), q["name"])
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
internal fun corridorMaxZoom(type: String?): Int = TripCorridor.maxZoom(type) // T130: in shared
