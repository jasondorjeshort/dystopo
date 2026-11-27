package io.github.jasondorjeshort.dystopo.app.areas

import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.ContourCache
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.app.online.OnlineService
import io.github.jasondorjeshort.dystopo.app.trips.Trips
import io.github.jasondorjeshort.dystopo.areas.AreaDetail
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
 *   cache and pinned. `GET /areas/download`: progress; `DELETE /areas/download`: cancel. T157: `demMaxZoom` (body or
 *   query) caps the elevation below `maxZoom` (vector to `min(maxZoom, 14)`), or `detail=full|medium|less` in the query sets
 *   the zooms (medium: `maxZoom` 14, `demMaxZoom` 13; its id differs from the less and full ones'); a box with a cap is
 *   refused (400: a region pack holds one zoom range).
 * - T95: `POST /areas/plan` with the same parameters: the corridor's manifest size, tiles per route, and whether the
 *   pinned budget allows it (`fits`, `refusal`), with nothing started (what a trip's Offline button shows). T369: a box
 *   (`bbox=`) is planned as "Download this area" plans it, as a rectangle; with no server set the plan's `cap` (`direct`,
 *   `vectorTiles`, `maxTiles` 3000, `maxBytes` 50000000, `over`, `refusal`) is the public vector source's cap, and over it
 *   `refusal` is the cap's sentence ("This area needs about 4,700 map tiles, and the public map source allows 3,000 a
 *   download. ..."), ahead of the budget's; every start refuses alike (409 with the sentence). `GET /areas/download` `cap`
 *   (`vectorBytes` fetched, `stopped` at 50 MB); `GET /areas/countries` `direct`, `overCap` and each row's `vectorTiles`
 *   per detail (state `over_cap`); `GET /areas/picker` `overCap` and `cap`; `GET /areas/resume` `why` `cap` for a record
 *   over it (not resumed by itself while no server is set; `autoResume.stopped` `cap`).
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
 * - `POST /areas/ui?open=1|0`: the Areas screen. T221: `detail=<id>` taps that area's row (its zooms and bounds open or close);
 *   T234: or a row id of `GET /areas/rows` (`pack:<name>`, `area:<id>`).
 * - T234 (ADR 0056, B1a), the one list of offline maps ([OfflineMapList]): `GET /areas/rows` gives
 *   `{rows:[{id,name,bytes,state,tone,detail,actions,pack,recordId,bounds,detailOpen}],count,ms}`, in the sheet's order, one row per
 *   pack name (`pack:<name>`: on the phone, named by a record, downloading, the region job's) and one per record with no pack or
 *   unreadable (`area:<id>`); `state` is the row's word as drawn, `actions` the labels its opened row offers. `POST
 *   /areas/rows/action?id=<row id>&action=<label>` runs one as its tap does (Remove: every record naming the pack, then the pack and
 *   its download; an update or a trail-network fetch is only stopped) and answers its `result` with the rows after it.
 *   T342: each row's `off` is its "Use on this phone" switch (true: the map is off, false: on, null: a row without one);
 *   `POST /areas/rows/off?id=pack:<name>&off=1|0` sets it as the switch does, without a restart (`name=<pack>` for `id`), and
 *   answers its `result` (`off`, `records` changed, `listed`, `offNames`, `active`, `inUse`) with the rows after it.
 *   T366: each row's `level` (`full`, `medium`, `less`, `low`, `off`; [DetailOutline]) and `outline` (`colour`, `width`, `dash`,
 *   `dots`, `fill`, `night`: how its outline and swatch are drawn in the map's palette now).
 * - T221, a removal you can see: `DELETE /areas/{id}` answers when the tiles are gone (`ms`, `tilesDeleted`); until then the area
 *   stays in `GET /areas` and `GET /areas/{id}` with `state` `removing` and `removing` (`done`, `total`, `line` as the row reads
 *   it, `ms`). `chunkDelayMs=N` (0 to 10000) waits after each chunk of 256 tiles, so a screenshot can catch the row.
 *   Each area in `GET /areas` has `detailOpen` and `detailLines` (what a tap on its row opens). The removal is in memory only.
 *   `GET /areas/picker` `fit`: the camera's last fitted `box` (a country's: `countryBox`) and its `centre`, the point at the middle
 *   of the band above the card; `GET /camera`'s centre lies below that point, since the card covers the map's lower part.
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
 *   `fits`, `refusal`) with `offerLessDetail` (when the full plan is refused); T157: `mediumDetail` (`zooms` 8 to 14,
 *   `demMaxZoom` 13, `id`, `bytes`, `tiles`, `line` "Medium detail (about 60 MB)", `fits`, `refusal`), and every plan's
 *   `demMaxZoom` and `detail` (`full`, `medium`, `less`, or null for other zooms). Over the budget its `refusal` is "This area is about 450 MB; 1.6 GB of your 2 GB offline budget is in use. Make the
 *   area smaller, choose less detail, or raise the budget in Settings › Storage." `GET /areas` gives each area
 *   `needsNewerApp` (a record of a version or kind this build cannot read, kept as it was, pinned by its `bounds` and
 *   `zooms`; `needsNewerAppReason` the raw sentence; Pin, Retry and Dismiss answer 409), a polygon's `rings`, `vertices`
 *   and `areaKm2`, a disc's `radiusM`, and `files` (records in `areas.json` and in the sibling `areas2.json`).
 *   `GET /areas/download` `recent` no longer repeats the job shown above it; `GET /areas/trips` `lastRun` is the last run
 *   that made, refused, cancelled or released a corridor (a run that did nothing leaves it).
 * - T142 (ADR 0041 §5), the area picker ([AreaPicker]), so the sheet is scripted and screenshotted:
 *   - `GET /areas/countries[?find=text][&wait=ms]`: the bundled countries ([CountrySizes]; all, or `Countries.find`'s matches
 *     in its order), each with `code`, `name`, `nameLong`, `continent`, the row's `detail` (`full`, `medium` or `less`: the
 *     deepest that fits the budget), `zooms`, `demMaxZoom`, `state` (`measuring`, `fits`, `over_budget`, `too_large`),
 *     `greyed`, `sizeText` (T157: the three sizes, "Less 28 MB · Medium 40 MB · Full 230 MB"), `sentence` (a greyed row's),
 *     `bytes`, `tiles`, `less`, `medium` and `full` (each `bytes`, `tiles`, `id`, `estimated`: the full detail is estimated
 *     from z14's cover when its floor is past the budget), `downloaded` (the row's mark) and `downloadedId`; with
 *     `wait` it measures the rows first. At the top `measured`, `greyed`, `tooLargeNames`, `pinnedBytes`, `budgetMb`, `heapTiles`
 *     (T152: the tiles the tile cache index holds comfortably; said, never a refusal), T170 `coverCache` (`CountrySizes.coverStats`).
 *     `POST /areas/countries[?filter=text]` opens the list in the sheet with that filter.
 *   - `POST /areas/country?code=<ADM0_A3 or ISO>[&detail=full|medium|less]`: the country's summary (the default detail without `detail`).
 *   - `POST /areas/draw[?ring=lat,lon;lat,lon;...]`: Draw an area (the ring, else four vertices from the current view);
 *     `GET /areas/draw[?wait=ms]` (also `GET /areas/picker`): the picker's state: `mode`, `name`, `detail`, `planned` (the
 *     size line is of the shape in view), `sizeLine`, `alternative` (the first other detail's line), T157 `alternatives`
 *     (`detail`, `line` of each detail not shown) and `hint` (the medium detail's line), `timeLine`, `refusal`, `canDownload`,
 *     `bytes`, `tiles`, `zooms`, `demMaxZoom`, `id`, `full`, `medium` and `less` (the plans), `drawing` (`ring`, `vertices`, `areaKm2`, `canUndo`,
 *     gesture counters), `around`, `country`. `POST /areas/draw/touch?op=drag&index=i[&mid=1]&to=lat,lon`, `op=tap&at=lat,lon`
 *     and `op=hold&index=i` act through the map view's own touch dispatch, as a finger would (drag a vertex or the midpoint
 *     after it, tap the map, long-press a vertex); `POST /areas/draw/undo` is Undo. Each answers the state (`wait` as above).
 *   - `POST /areas/around?at=lat,lon[&radiusM=][&name=][&sheet=1]`: "Keep offline around here" (as the long-press card's
 *     entry, or a search result's with `name`).
 *   - `POST /areas/picker?detail=full|medium|less&name=&radiusM=`: the summary's choices; `POST /areas/picker/download` its
 *     Download (202, the download's start; 409 with the refusal past the budget); `POST /areas/picker/close` Cancel.
 *   - T153: `POST /areas/region[?bbox=w,s,e,n][&zoom=z][&wait=ms]`: the overlay's "Download this region" (the visible map and
 *     its zoom by default): the region's summary; `GET /areas/picker` adds `region` (`bbox`, `viewZoom`, `defaultDetail`,
 *     `packFits`, `path` `pack` or `area`, `pathLine`, `packTiles`, `limits`); its Download answers `path`. T192: on the
 *     pack path `sizeLine` counts the trail graph and `graphBytes` is its estimate (null on the area path).
 *   - `POST /areas/{id}/rename?name=`: the card's Rename.
 * - T152 (ADR 0041 §3's T152 note): no heap refusal. Every plan (`POST /areas/plan`, the picker's `full` and `less`) carries
 *   its estimate (`bytes`, `estimated`, `sizeLine` "About ..."), `rows` (what the download walks), `refused` (the budget's
 *   refusal only), `pastHeapLimit` and `heapTiles` (past the tile cache index's comfortable size: said, and Download proceeds)
 *   and `manifestMs`; `GET /areas/draw` adds `refused`, `pastHeapLimit` and `heapTiles` for the plan shown.
 *   `GET /areas/download` `rows`: `planned`, `started`, `done` (whole), `wholePrefix` (the rows before this index of the walk
 *   are whole), `current` and `lastWhole` (`z`, `route`, `y`, `x0`, `x1`), `order` (zoom, route, y, x0). In direct mode a
 *   download is exempt from the session cap (`GET /online` `direct.sessionCapExempt`).
 * - T151, interrupted downloads ([ResumeCheck]): a record carries `cursor` (`z`, `route`, `y`, `x0`, `x1` of the last row of
 *   the whole prefix, `wholeRows`, `failed` rows per route and zoom with `failedTiles`, `savedAt`) while unfinished,
 *   `cancelled` once the user cancelled it, and `autoResume` (`failures`, `attempts`, `nextAt`, `stopped`: `failures` or
 *   `refused` with `budgetMb`, `runFrom`, `trigger`). `GET /areas/resume`: the last check (`triggers`, `checkMs`, `records`,
 *   `unfinished` with each one's `why`: running, ending, cancelled, stopped, refused, backoff, ready; `started`, `waitingFor`,
 *   `note`, `nextAt`), `runs`, `triggersSeen`, `queued`, `timerAt`, `repairedAtStart` (records found left downloading),
 *   `unfinishedNow` (the same read now, nothing started) with `nowMs`, `backoffMs`, `maxFailures`. `POST /areas/resume[?trigger=]`
 *   runs a check now (trigger `debug` by default, which skips a backoff's wait like app start; `backoff` does not).
 *   `GET /areas/download` `resume`: `trigger`, `from` (the cursor it resumed from), `skippedTiles`, `failedAgain`, `stalled`,
 *   `stalledByNetwork`, `cursorSaves`, `cursor`. `DELETE /areas/{id}` answers `ms`; each area's `coverage` adds `rows` and `ms`
 *   (counted row by row).
 * - T377, the fixture for "an area kept as tiles" (wave 49; T378 and T386 reuse it): `POST /areas/fixture/tiles?pack=<name>`
 *   records a drawn area as a finished tile area from tiles already on the phone, with no request to the network: the area
 *   (a polygon `ring=lat,lon;...`, by default a ring around Paradise on Mount Rainier; `detail=full|medium|less`, medium by
 *   default; `name=`, "Paradise tiles" by default) gets the vector tiles of its download's rows that the pack <name>'s vector
 *   archive holds, copied into the online cache under the keys an area download writes (`Areas.keyPrefixes`, the current
 *   version), a manifest of those rows (the vector item only: tiles the pack lacks are left out, the elevation is not
 *   seeded) and a record `done`, pinned, from the online source in use (409 without one). `skipOpen=1` copies as a download
 *   does next to an open map (`AreaDownload.fetchTile`): a tile an open map answers is left out of the cache but stays in the
 *   manifest, so the area holds fewer tiles than its shape (with the pack on, none). Answers `id`, `name`, `zooms`,
 *   `copied`, `skippedOpenMap` and `outsidePack` per zoom, `bytes`, `ms`, `keyPrefix`, `network` false and the record.
 *   `DELETE /areas/{id}` removes it with its tiles. The pack is read even while it is off (`POST /areas/rows/off`).
 * - T386, a saved area's detail changed with its shape kept (`DetailChange` in `shared`): `GET /areas/{id}/detail` gives
 *   `detail` (the recorded zooms' `full`, `medium`, `less`), `held` and `label` (what it holds whole: below `detail` while a raise
 *   waits or runs), `zooms`, `demMaxZoom`, `bytes`, `raise` (`minZoom`, `maxZoom`, `demMaxZoom` held, `deferred` after Later, the
 *   target's `label`, the added `tiles`, `vectorTiles`, `bytes`), `offered`, `refusal` (why no Detail: a trip's corridor "Its detail
 *   follows the trip.", a download running, not finished), `lowering` (`to`, `done`, `total`, `ms` while tiles are freed),
 *   `sheetOpen`, `choice`, and `options`: the sheet's three choices (`detail`, `label`, `now`, `pending`, `line`, `note`, `confirm`,
 *   `bytes`, `delta`, `freedBytes`, `counted`, `added` {`bytes`, `tiles`, `vectorTiles`}, `refusal`, `overCap`, `change` {`from`,
 *   `to`, `added` and `dropped` zooms per route, `fromId`, `toId`}, `id`). `POST /areas/{id}/detail?level=less|medium|full[&start=0]
 *   [&chunkDelayMs=]` makes the change as the sheet's confirm does and answers when it is made: a lower re-keys the record (the new
 *   `id`, `fromId`), frees the dropped zooms' tiles no other area or pin holds (`freedBytes`, `tilesDeleted`, `bytesBefore`,
 *   `bytes` counted again, `waitedForIndex`) and fetches nothing; a raise re-keys it with its `raise` and saves the added tiles'
 *   manifest (`raise`: `tiles`, `vectorTiles`, `bytes`, `perRoute` zooms, `deferred`), then starts their download (`download`), or
 *   with `start=0` ("Later") leaves it waiting, not started by the launch's resume check (`GET /areas/resume` `why` `deferred`).
 *   Over the public source's cap (direct mode, the added vector tiles alone) or the budget it answers 409 with the sentence and
 *   changes nothing. `POST /areas/rows/action?action=Start` starts a raise that waits; `action=Detail` opens the sheet.
 *   `POST /areas/ui?sheet=<id>` opens the row and its sheet (`sheet=0` closes it), `choose=less|medium|full` shows a choice's
 *   confirm. `GET /areas` adds `lowerings`; each record carries `raise` while one waits or runs.
 */
object AreaRoutes {
    private val item = Regex("^/areas/(a[0-9a-f]{4,16})(/manifest|/pin|/retry|/dismiss|/rename|/detail)?$") // T386: /detail

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
        picker(app, r)?.let { return it } // T142
        when (r.method to r.path) {
            "GET" to "/areas" -> return Response.json(Areas.json())
            "GET" to "/areas/download" -> return Response.json(AreaDownload.json() ?: mapOf("state" to "idle"))
            "DELETE" to "/areas/download", "POST" to "/areas/download/cancel" -> return Response.json(mapOf("cancelled" to AreaDownload.cancel(), "download" to AreaDownload.json()))
            "POST" to "/areas/download" -> return Response.json(download(r), 202)
            "POST" to "/areas/plan" -> return Response.json(AreaDownload.plan(planArea(area(r).second)).json())
            "POST" to "/areas/trim" -> return Response.json(trim(r))
            "GET" to "/areas/trips" -> return Response.json(TripCorridors.json()) // T130
            "POST" to "/areas/trips" -> { // T130: a reconcile now (`wait=ms` answers after it)
                TripCorridors.request("debug route")
                r.query["wait"]?.toLongOrNull()?.let { TripCorridors.drain(it.coerceIn(0, 600_000)) }
                return Response.json(TripCorridors.json())
            }
            "GET" to "/areas/resume" -> return Response.json(ResumeCheck.json()) // T151
            "POST" to "/areas/resume" -> return Response.json(ResumeCheck.runNow(r.query["trigger"]?.takeIf { it.isNotBlank() } ?: ResumeCheck.TRIGGER_DEBUG))
            "GET" to "/areas/prefetch" -> return Response.json(AheadPrefetch.json())
            "POST" to "/areas/prefetch" -> {
                r.query["on"]?.let { AheadPrefetch.enabled = it == "1" || it == "true" }
                return Response.json(AheadPrefetch.json())
            }
            "POST" to "/areas/ui" -> {
                val open = r.query["open"] != "0"
                app.map.onMain {
                    Areas.screenOpen = open
                    // T221: as a tap on the row; T234: a row id of `GET /areas/rows` too (`pack:<name>`, `area:<id>`).
                    r.query["detail"]?.let { d -> Areas.get(d.removePrefix(OfflineMapList.AREA))?.let { Areas.toggleDetail(it.id) } ?: d.takeIf { it.startsWith(OfflineMapList.PACK) }?.let { Areas.toggleDetail(it) } }
                    // T386: `sheet=<id>` opens that area's row and its Detail sheet (`sheet=0` closes it), `choose=` picks a choice
                    r.query["sheet"]?.let { s ->
                        val id = Areas.get(s.removePrefix(OfflineMapList.AREA))?.id
                        if (id != null) Areas.detailOpen = Areas.detailOpen + id
                        Areas.detailChoice = null
                        Areas.detailSheet = id
                    }
                    r.query["choose"]?.let { c -> Areas.detailChoice = c.takeIf { it in DETAILS } }
                }
                return Response.json(mapOf("open" to open))
            }
            "GET" to "/areas/rows" -> return Response.json(rowsJson()) // T234
            "POST" to "/areas/rows/off" -> { // T342: a map's "Use on this phone"
                val name = r.query["name"] ?: r.query["id"]?.removePrefix(OfflineMapList.PACK)?.takeIf { r.query["id"]!!.startsWith(OfflineMapList.PACK) }
                    ?: throw IllegalArgumentException("id=pack:<name> (GET /areas/rows) or name=<map>")
                val off = when (r.query["off"]) { "1", "true" -> true; "0", "false" -> false; else -> throw IllegalArgumentException("off=1|0") }
                return Response.json(linkedMapOf("id" to OfflineMapList.PACK + name, "result" to io.github.jasondorjeshort.dystopo.app.packs.PackManager.get(app).setOff(name, off)) + rowsJson())
            }
            "POST" to "/areas/fixture/tiles" -> return Response.json(seedTileArea(app, r.query)) // T377: the wave's fixture
            "POST" to "/areas/rows/action" -> { // T234: a row's action, as its tap
                val inputs = offlineMapInputs(app)
                val id = r.query["id"] ?: throw IllegalArgumentException("id=<row id> (GET /areas/rows)")
                val row = OfflineMapList.rows(inputs).firstOrNull { it.id == id } ?: throw NoSuchElementException("no row '$id' (GET /areas/rows)")
                val action = r.query["action"] ?: throw IllegalArgumentException("action=<one of ${row.actions}>")
                return Response.json(linkedMapOf("id" to id, "action" to action, "result" to runRowAction(row, action, inputs, app)) + rowsJson())
            }
        }
        val m = item.matchEntire(r.path) ?: return Response.notFound()
        val id = m.groupValues[1]
        if (r.method == "GET" && m.groupValues[2].isEmpty()) Areas.removals.firstOrNull { it.record.id == id }?.let { return Response.json(removalJson(it)) } // T221
        val rec = Areas.get(id) ?: throw NoSuchElementException("no area '$id'")
        return when (r.method to m.groupValues[2]) {
            "GET" to "" -> Response.json(rec.json() + mapOf("olderThanSource" to Areas.older(rec), "coverage" to Areas.coverage(rec), "manifest" to Areas.manifest(rec.id)?.summary()))
            "GET" to "/manifest" -> Response.json(Areas.manifest(rec.id)?.json() ?: throw NoSuchElementException("area $id has no manifest (a pack-backed area: see its pack)"))
            "POST" to "/pin" -> Response.json(TripCorridors.userPin(rec.id, r.query["on"] != "0").json()) // T130: on a trip's corridor, also Keep offline or Stop keeping
            "POST" to "/retry" -> Response.json(retry(rec), 202) // T106: the card's Retry (a download again)
            "POST" to "/dismiss" -> Response.json(mapOf("area" to Areas.dismiss(rec.id)?.json(), "removed" to (Areas.get(rec.id) == null))) // T106
            // T130: a trip's corridor removed by hand is not made again. T221: `chunkDelayMs` slows the deletes for a screenshot.
            "DELETE" to "" -> Response.json(TripCorridors.userRemove(rec.id, r.query["chunkDelayMs"]?.toLongOrNull()?.coerceIn(0, 10_000) ?: 0))
            "POST" to "/rename" -> Response.json(rename(rec.id, r.query["name"] ?: throw IllegalArgumentException("name=<new name>")).json()) // T142
            "GET" to "/detail" -> Response.json(detailJson(rec)) // T386
            "POST" to "/detail" -> { // T386: the sheet's choice, run here (answers when the tiles are freed or the raise is set)
                val level = r.query["level"]?.let { AreaDetail.byKey(it) } ?: throw IllegalArgumentException("level must be ${DETAILS.joinToString(", ")}")
                val start = r.query["start"] != "0"
                Response.json(AreaDownload.changeDetail(rec.id, level, start, r.query["chunkDelayMs"]?.toLongOrNull()?.coerceIn(0, 10_000) ?: 0))
            }
            else -> Response.notFound()
        }
    }

    /** T142: the picker's routes (see the object comment); null for any other path. */
    private fun picker(app: DystopoApp, r: Request): Response? {
        val q = r.query
        return when (r.method to r.path) {
            "GET" to "/areas/countries" -> Response.json(countries(q))
            "POST" to "/areas/countries" -> {
                app.map.onMain { AreaPicker.openCountries(); q["filter"]?.let { AreaPicker.filter = it } }
                Response.json(pickerState(app, q))
            }
            "POST" to "/areas/country" -> {
                val code = q["code"] ?: throw IllegalArgumentException("code=<country code> (GET /areas/countries)")
                val detail = q["detail"]?.also { require(it in DETAILS) { "detail must be ${DETAILS.joinToString(", ")}" } }
                app.map.onMain { AreaPicker.openCountry(code, detail) }
                Response.json(pickerState(app, q))
            }
            "POST" to "/areas/draw" -> {
                val ring = q["ring"]?.let { s -> (AreaOfInterest.fromQuery(mapOf("ring" to s)).shape as io.github.jasondorjeshort.dystopo.areas.AreaShape.Polygon).rings.single() }
                check(app.map.onMain { AreaPicker.startDraw(ring) }) { "the map is not laid out" }
                Response.json(pickerState(app, q))
            }
            "GET" to "/areas/draw", "GET" to "/areas/picker" -> Response.json(pickerState(app, q))
            "POST" to "/areas/draw/undo" -> {
                app.map.onMain { AreaDraw.undo() }
                Response.json(pickerState(app, q))
            }
            "POST" to "/areas/draw/touch" -> {
                check(app.map.onMain { AreaDraw.active }) { "not drawing (POST /areas/draw first)" }
                fun point(k: String) = q[k]?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 2 } ?: throw IllegalArgumentException("$k=lat,lon")
                fun index() = q["index"]?.toIntOrNull() ?: throw IllegalArgumentException("index=<vertex>")
                val ok = when (q["op"]) {
                    "drag" -> point("to").let { (lat, lon) -> app.map.onMain { AreaDraw.simulateDrag(index(), q["mid"] == "1", lat, lon) } }
                    "tap" -> point("at").let { (lat, lon) -> app.map.onMain { AreaDraw.simulateTap(lat, lon) } }.also {
                        Thread.sleep(android.view.ViewConfiguration.getDoubleTapTimeout() + 200L) // the vertex lands once no second tap came
                    }
                    "hold" -> app.map.onMain { AreaDraw.simulatePress(index()) }?.let { t0 ->
                        Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 250L)
                        app.map.onMain { AreaDraw.simulateRelease(t0) }
                        true
                    } ?: false
                    else -> throw IllegalArgumentException("op must be drag, tap or hold")
                }
                check(ok) { "the handle or the point is off screen" }
                Response.json(pickerState(app, q))
            }
            "POST" to "/areas/around" -> {
                val at = q["at"]?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 2 } ?: throw IllegalArgumentException("at=lat,lon")
                val radius = q["radiusM"]?.let { it.toDoubleOrNull() ?: throw IllegalArgumentException("radiusM must be metres") }
                app.map.onMain { AreaPicker.startAround(at[0], at[1], q["name"]?.trim()?.ifEmpty { null }, fromSheet = q["sheet"] == "1", radiusM = radius) }
                Response.json(pickerState(app, q))
            }
            "POST" to "/areas/picker" -> {
                val detail = q["detail"]?.also { require(it in DETAILS) { "detail must be ${DETAILS.joinToString(", ")}" } }
                val radius = q["radiusM"]?.let { it.toDoubleOrNull() ?: throw IllegalArgumentException("radiusM must be metres") }
                app.map.onMain {
                    detail?.let { AreaPicker.detailChoice = it }
                    q["name"]?.let { AreaPicker.name = it }
                    radius?.let { AreaPicker.typed = ""; AreaPicker.setRadius(it) }
                }
                Response.json(pickerState(app, q))
            }
            "POST" to "/areas/picker/download" -> Response.json(AreaPicker.download(), 202)
            "POST" to "/areas/region" -> { // T153
                AreaPicker.openRegionOffMain(q["bbox"]?.let { regionBox(it) }, q["zoom"]?.let { it.toDoubleOrNull() ?: throw IllegalArgumentException("zoom must be a number") })
                Response.json(pickerState(app, q))
            }
            "POST" to "/areas/picker/close", "DELETE" to "/areas/picker" -> {
                app.map.onMain { AreaPicker.close() }
                Response.json(pickerState(app, q))
            }
            else -> null
        }
    }

    /** The picker's state; with `wait=ms`, once the size line is of the shape in view (or the time is up). */
    private fun pickerState(app: DystopoApp, q: Map<String, String>): Map<String, Any?> {
        val until = System.currentTimeMillis() + (q["wait"]?.toLongOrNull() ?: 0L).coerceIn(0, 300_000)
        while (true) {
            val j = app.map.onMain { AreaPicker.json() }
            if (j["planned"] == true || j["mode"] == null || j["mode"] == "countries" || System.currentTimeMillis() >= until) return j
            Thread.sleep(100)
        }
    }

    /** T142: `GET /areas/countries`: the rows as the list shows them (see the object comment). */
    private fun countries(q: Map<String, String>): Map<String, Any?> {
        val all = io.github.jasondorjeshort.dystopo.boundaries.Countries.all
        val list = q["find"]?.let { io.github.jasondorjeshort.dystopo.boundaries.Countries.find(it, all) } ?: all
        val codes = list.map { it.code }
        val wait = (q["wait"]?.toLongOrNull() ?: 0L).coerceIn(0, 600_000)
        if (wait > 0) { CountrySizes.requestExtra(codes); CountrySizes.await(codes, wait) }
        val pinned = OnlineService.get(DystopoApp.instance).cache.pinnedBytes
        val budget = AppSettings.pinnedCacheMb
        val sizes = CountrySizes.entries
        val records = Areas.all()
        val direct = AreaDownload.direct() // T369
        val rows = list.map { c ->
            val e = sizes[c.code]
            val rec = records.firstOrNull { r -> e != null && r.id in e.ids }
            val row = countryRow(e, pinned, rec?.takeIf { it.pinned }?.bytes ?: 0L, budget, direct)
            fun m(x: CountryMeasure?, id: String?) = x?.let { linkedMapOf("bytes" to it.bytes, "tiles" to it.tiles, "tooLarge" to it.tooLarge, "estimated" to it.estimated, "id" to id, "vectorTiles" to it.vectorTiles) }
            val detail = AreaDetail.byKey(row.detail) ?: AreaDetail.LESS
            c to linkedMapOf(
                "code" to c.code, "iso3" to c.iso3, "name" to c.name, "nameLong" to c.nameLong, "continent" to c.continent, "toleranceM" to c.toleranceM,
                "detail" to row.detail, "zooms" to listOf(AreaDetail.MIN_ZOOM, detail.maxZoom), "demMaxZoom" to detail.demMaxZoom,
                "state" to row.state.name.lowercase(), "greyed" to row.greyed, "sizeText" to row.sizeText, "sentence" to row.sentence,
                "bytes" to row.bytes, "tiles" to row.tiles, "less" to m(e?.less, e?.lessId), "medium" to m(e?.medium, e?.mediumId), "full" to m(e?.full, e?.fullId),
                "downloaded" to downloadMark(rec?.state), "downloadedId" to rec?.id,
            )
        }
        return linkedMapOf(
            "total" to all.size, "shown" to rows.size, "measured" to rows.count { it.second["state"] != "measuring" },
            "greyed" to rows.count { it.second["greyed"] == true }, "tooLargeNames" to rows.filter { it.second["state"] == "too_large" }.map { it.first.name },
            "direct" to direct, "overCap" to rows.count { it.second["state"] == "over_cap" }, // T369
            "pinnedBytes" to pinned, "budgetMb" to budget, "heapTiles" to AreaDownload.heapTiles(), "coverCache" to CountrySizes.coverStats, // T170
            "countries" to rows.map { it.second },
        )
    }

    /** T234: `GET /areas/rows`: the one list's rows as the sheet draws them, with `detailOpen` per row, and how long the read took. */
    private fun rowsJson(): Map<String, Any?> {
        val t0 = System.nanoTime()
        val rows = OfflineMapList.rows(offlineMapInputs())
        val open = Areas.detailOpen
        return linkedMapOf("rows" to rows.map { it.json(it.detailKey in open) }, "count" to rows.size, "ms" to Math.round((System.nanoTime() - t0) / 1e5) / 10.0)
    }

    /**
     * T369: a box's plan as "Download this area" plans it ([regionArea]: the box as a rectangle at its zooms), so `POST
     * /areas/plan?bbox=` reads the size and the cap's refusal; any other shape as it is.
     */
    private fun planArea(a: AreaOfInterest): AreaOfInterest {
        val s = a.shape as? io.github.jasondorjeshort.dystopo.areas.AreaShape.Box ?: return a
        return regionArea(io.github.jasondorjeshort.dystopo.packs.PackBounds(s.west, s.south, s.east, s.north), a.maxZoom, a.name).let { it.withZooms(a.minZoom, a.maxZoom, a.demMaxZoom) }
    }

    /** T377: the fixture's default ring, around Paradise on Mount Rainier (a public place), inside the Rainier pack. */
    internal const val FIXTURE_RING = "46.781,-121.752;46.781,-121.718;46.797,-121.718;46.797,-121.752"

    /**
     * T377: `POST /areas/fixture/tiles` (see the object comment): a drawn area recorded as a finished tile area from the vector
     * tiles of the pack [q] names, copied into the online cache; nothing is fetched.
     */
    private fun seedTileArea(app: DystopoApp, q: Map<String, String>): Map<String, Any?> {
        val t0 = System.currentTimeMillis()
        val packName = q["pack"] ?: throw IllegalArgumentException("pack=<name> (GET /areas/rows: pack:<name>)")
        val listed = io.github.jasondorjeshort.dystopo.app.packs.PackManager.get(app).find(packName) ?: throw NoSuchElementException("no pack '$packName' on the phone")
        val vector = listed.files.vector ?: throw IllegalArgumentException("pack '$packName' has no vector archive")
        val service = OnlineService.get(app)
        val fetcher = service.fetcher ?: throw IllegalStateException("no online source in use (an area's tiles are kept under its keys)")
        val ring = (AreaOfInterest.fromQuery(mapOf("ring" to (q["ring"] ?: FIXTURE_RING))).shape as io.github.jasondorjeshort.dystopo.areas.AreaShape.Polygon).rings.single()
        val detail = AreaDetail.byKey(q["detail"] ?: "medium") ?: throw IllegalArgumentException("detail must be ${DETAILS.joinToString(", ")}")
        val name = q["name"]?.trim()?.ifEmpty { null } ?: "Paradise tiles"
        val area = AreaOfInterest(io.github.jasondorjeshort.dystopo.areas.AreaShape.Polygon(listOf(ring)), AreaDetail.MIN_ZOOM, AreaDetail.MIN_ZOOM + 1).at(detail).withName(name)
        val skipOpen = q["skipOpen"] == "1"
        val item = AreaDownload.localManifest(area, fetcher.server).items.filterIsInstance<io.github.jasondorjeshort.dystopo.areas.TilesItem>()
            .first { it.route == io.github.jasondorjeshort.dystopo.areas.AreaManifest.ROUTE_OSM }
        val prefix = Areas.keyPrefixes(item.route, fetcher.server, held = false).first()
        val packs = runCatching { app.tiles.packs }.getOrNull()
        val held = HashMap<Int, io.github.jasondorjeshort.dystopo.areas.TileSet>()
        val copied = sortedMapOf<Int, Int>()
        val skipped = sortedMapOf<Int, Int>()
        val outside = sortedMapOf<Int, Int>()
        var bytes = 0L
        io.github.jasondorjeshort.dystopo.app.FileRangeSource(vector).use { src ->
            val reader = io.github.jasondorjeshort.dystopo.pmtiles.PmtilesReader.open(src, mapOf(io.github.jasondorjeshort.dystopo.pmtiles.Compression.GZIP to io.github.jasondorjeshort.dystopo.app.gunzip))
            for ((z, set) in item.tiles) set.forEachRow { y, x0, x1 ->
                for (x in x0..x1) {
                    val tile = reader.tile(z, x, y)
                    if (tile == null) {
                        outside[z] = (outside[z] ?: 0) + 1
                        continue
                    }
                    held.getOrPut(z) { io.github.jasondorjeshort.dystopo.areas.TileSet(z) }.add(x, y)
                    // As AreaDownload.fetchTile: a tile an open map answers is not fetched (with the pack off, none is).
                    if (skipOpen && packs?.osmFor(z, x, y) != null) {
                        skipped[z] = (skipped[z] ?: 0) + 1
                        continue
                    }
                    service.cache.put(prefix + "/" + z + "/" + x + "/" + y + "." + item.ext, tile)
                    bytes += tile.size
                    copied[z] = (copied[z] ?: 0) + 1
                }
            }
        }
        val tiles = io.github.jasondorjeshort.dystopo.areas.TilesItem(item.route, item.ext, item.url, held.toSortedMap(), bytes)
        Areas.saveManifest(io.github.jasondorjeshort.dystopo.areas.AreaManifest(
            area.id, name, area, io.github.jasondorjeshort.dystopo.areas.AreaManifest.STATUS_READY, listOf(tiles),
            source = "phone (debug fixture from the pack $packName)", generatedAt = java.time.Instant.now().toString(),
        ))
        val old = Areas.get(area.id)
        Areas.put(Areas.Record(
            id = area.id, name = name, area = area, createdAt = old?.createdAt ?: System.currentTimeMillis(), pinned = true, state = "done",
            source = fetcher.server, bytes = bytes, estimateBytes = bytes, tiles = copied.values.sum(), versions = fetcher.currentVersions(),
            downloadMs = System.currentTimeMillis() - t0,
        ))
        return linkedMapOf(
            "id" to area.id, "name" to name, "pack" to packName, "detail" to detail.key, "zooms" to held.keys.sorted(), "skipOpen" to skipOpen,
            "copied" to copied.mapKeys { it.key.toString() }, "skippedOpenMap" to skipped.mapKeys { it.key.toString() },
            "outsidePack" to outside.mapKeys { it.key.toString() },
            "bytes" to bytes, "ms" to System.currentTimeMillis() - t0, "keyPrefix" to prefix, "network" to false, "area" to Areas.get(area.id)?.json(),
        )
    }

    /** T386: `GET /areas/{id}/detail` (see the object comment). */
    private fun detailJson(rec: Areas.Record): Map<String, Any?> {
        val refusal = AreaDownload.detailRefusal(rec)
        val raise = Areas.raiseManifest(rec.id)
        return linkedMapOf(
            "id" to rec.id, "name" to rec.name, "detail" to AreaDetail.of(rec.area)?.key, "held" to AreaDetail.of(rec.heldArea)?.key, "label" to detailLabel(rec.heldArea),
            "zooms" to listOf(rec.area.minZoom, rec.area.maxZoom), "demMaxZoom" to rec.area.demMaxZoom, "state" to rec.state, "bytes" to rec.bytes, "tiles" to rec.tiles,
            "raise" to rec.raise?.json()?.plus(mapOf("label" to detailLabel(rec.area), "tiles" to raise?.tileCount, "vectorTiles" to raise?.vectorTileCount, "bytes" to raise?.bytes)),
            "offered" to OfflineMapList.detailOffered(rec), "refusal" to refusal,
            "lowering" to Areas.lowerings.firstOrNull { it.id == rec.id }?.let { linkedMapOf("to" to it.to, "done" to it.done, "total" to it.total, "ms" to System.currentTimeMillis() - it.startedAt) },
            "sheetOpen" to (Areas.detailSheet == rec.id), "choice" to Areas.detailChoice,
            "options" to if (refusal == null) runCatching { AreaDownload.detailOptions(rec.id).map { it.json() } }.getOrElse { listOf(mapOf("error" to it.message)) } else null,
        )
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
