package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.online.OnlineTiles
import io.github.jasondorjeshort.dystopo.app.packs.PackManager
import io.github.jasondorjeshort.dystopo.app.packs.PacksUi
import io.github.jasondorjeshort.dystopo.packs.Holdings
import io.github.jasondorjeshort.dystopo.packs.VectorProfile
import io.github.jasondorjeshort.dystopo.tiles.ShadeTiles
import kotlin.math.floor

/**
 * Debug routes for packs, the files behind the offline maps (registered with one line in [Routes]):
 * - T354 `GET /holdings[?z=&lat=&lon=]` (default: the camera's zoom and centre): ADR 0066's lookup ([Holdings]) at the
 *   point, for the vector tile at the zoom and the elevation tile one zoom deeper (`vector`, `dem`): `serves`, `servesKind`
 *   and `depth` (the holding that answers now and its stored maximum), `members` (every holding that answers the tile,
 *   depth first: `name`, `kind` map, area, cache or overview, `depth`, `holds` stores, synthesises or edge, `serves`,
 *   `chosen`, vector `profile`), `off` (the maps switched off that would), `network` (`storedMaxZoom`, `adds`: the deeper
 *   zoom the network would add, `ahead`: it would go ahead under More detail from online, not yet used by a tile path;
 *   `allowed`, `onlineDetail`, `notAllowedBecause`), `tilePathServes` (what the tile paths answer today, from the maps
 *   and the overview alone); `overviewRule` (T341's answer for the vector tile, `GET /why`'s `online.vector`); `readZoom`
 *   (the zoom the sky views read the point at); `holdings` (every input: `name`, `kind`, `vector` and `dem` zooms and box,
 *   `profile`, `chosen`, `off`); `online` (the online member: `vectorMaxZoom`, `demMaxZoom` stored, `allowed`,
 *   `onlineDetail`, `notAllowedBecause`; null with no source set). `GET /why`'s `maps` and `online.vector` read the same answer.
 * - `GET /packs`: every pack on the device (name, bounds, zooms, bytes, attribution, per-archive
 *   header facts) and which one is active. T352: `notOpened`, each archive the last open left closed (`pack`, `archive`
 *   `dem` or `osm`, `file`, `reason`: the exception or `short: <bytes> of <expected> bytes`, `dropped`: the whole pack left
 *   out), also one logcat line `PackSet.open: ...` under the tag `Dystopo` at each open.
 *   T342: each pack's `off` ("Use on this phone" off: left closed, never in `notOpened`; `POST /areas/rows/off`).
 * - `POST /packs/activate?name=`: switch packs; the camera moves to the pack's bounds.
 * - `POST /packs/download?url=[&name=][&activate=true]`: start (or resume) downloading a pack pair from
 *   a static host; `url` names `<name>-dem.pmtiles`, `<name>-outdoors.pmtiles` or the `<name>` prefix.
 * - `GET /packs/download`: download progress (bytes, percent, rate, resume offsets, HTTP responses); T222: `freeBytes` (usable now, or the figure below; the check itself also counts cached files the system would clear), and per job
 *   what its row says: `word` (`waiting`, `downloading`, `paused`, `stopped`), `sentence`, `actions` (their labels), `failure`
 *   (`connection`, `server`, `notFound`, `damaged`, `refused`, `build`, `space`, `other`), `shortBytes`, `resumedAtLaunch`; `error` stays the raw text.
 * - `POST /packs/download/retry?name=` (T222): Retry and Resume now: a stopped or paused job runs again from its part files.
 * - `POST /packs/region/retry` (T222): the same for the region job, which builds again when it stopped before its download.
 * - `POST /packs/download/free?bytes=` (T222): a figure for the free space, for the free-space check; without `bytes`, the phone's own again.
 * - `DELETE /packs/{name}` (or `POST /packs/delete?name=`): delete a pack, cancelling its download.
 * - `POST /packs/ui?show=true|false[&catalog=1|0][&fold=1|0][&full=1][&detail=]`: open or close the Offline maps list (T235,
 *   ADR 0056 B1b: the one list, the same as `POST /areas/ui`; for screenshots); `catalog=1` opens the fold "More ways to add a
 *   map" and its "From server" section (the list at its lower height, catalog outlines on the map, camera fitted to them);
 *   `fold=1|0` opens or closes the fold alone; `full=1` is the "Full list" action (the full height, the catalog closed). T222:
 *   `detail=cat:<id>` opens a catalog row's detail and `pack:<name>` (or `job:<name>`) or `area:<id>` a row of the list, as a tap
 *   on it does (empty: none). Answers `visible`, `detail`, `rowDetail` (the list's open rows), `catalogOpen`, `foldOpen` (null:
 *   open while the list is empty), `height` (`full` or `lower`), `firstVisibleItem` and `localRows` (every row of the list).
 *
 * T53, the server's pack catalog (`GET /catalog` on the server set in `/settings?server=`):
 * - `GET /packs/catalog[?refresh=1][&server=]`: the catalog with each pack's status on this device
 *   (`available`, `checking` while the installed files are hashed, `installed`, `update`, `downloading`),
 *   installed hashes, download progress, and the map overlay state (`overlay.outlines`).
 * - `POST /packs/catalog/download?id=[&activate=true]`: download a catalog pack; every archive's SHA-256 is
 *   checked against the catalog before the pack is used (a mismatch fails the job with `hash mismatch`).
 * - `POST /packs/catalog/refresh[?server=]` (T57): fetch the catalog again now (the open "From server" section
 *   reloads too); JSON as `GET /packs/catalog`.
 * - `GET /packs/update`: installed packs whose catalog content hash differs (fresh catalog).
 * - `POST /packs/update?name=`: update one: only the changed archives are downloaded and verified, then
 *   swapped in while the pack is closed.
 */
object PacksRoutes {
    private val packPath = Regex("^/packs/([^/]+)$")

    // ---- T354: GET /holdings, ADR 0066's lookup at a point ----

    /**
     * T354: the lookup's answer at ([lat], [lon]) and [zoom], which `GET /holdings` and `GET /why` both report (one answer,
     * two routes): the vector tile at the map's zoom and the elevation tile one zoom deeper (`GET /why`'s two tiles), each
     * over every holding ([vector], [dem]) and over the holdings the tile paths read today, the maps and the world overview
     * ([vectorNow], [demNow]: what `PackSet.osmFor` and `demFor` answer; T356 moves the tile paths onto the whole lookup);
     * [overviewRule] T341's rule for the vector tile ([Holdings.vectorTile], the tile path's at the overview's zooms) read
     * from the same answer; [readZoom] the zoom the sky views read the point's terrain at ([Holdings.readZoom], capped at
     * `ShadeTiles.POINT_ZOOM` as the Horizon view caps it; outside every map it names the online source's stored maximum,
     * where the terrain read still uses the URL range until T368).
     */
    class HoldingsHere(
        val zoom: Double, val lat: Double, val lon: Double, val holdings: List<Holdings.Holding>, val online: Holdings.Online?,
        val vector: Holdings.Here, val dem: Holdings.Here, val vectorNow: Holdings.Here, val demNow: Holdings.Here,
        val overviewRule: Holdings.Answer, val readZoom: Int?,
    ) {
        fun json(): Map<String, Any?> = linkedMapOf(
            "zoom" to zoom, "lat" to lat, "lon" to lon,
            "vector" to vector.json() + ("tilePathServes" to vectorNow.serving?.holding?.name),
            "dem" to dem.json() + ("tilePathServes" to demNow.serving?.holding?.name),
            "overviewRule" to overviewRule.json(), "readZoom" to readZoom,
            "holdings" to holdings.map { it.json() }, "online" to online?.json(),
        )
    }

    /**
     * T354: everything the phone holds, as the lookup's inputs: the open maps and the world overview ([PackSet.holdings]), the
     * maps that are off and the finished tile areas (`Areas.lookupHoldings`), and the online tile cache (`cache`: a tile is
     * held where the fetcher's cache has it, `TileCache.contains` through [io.github.jasondorjeshort.dystopo.app.online.OnlineFetcher.hasCached];
     * its depth the online source's stored maximum; absent with no source set, when nothing reads it); with the online member.
     */
    fun holdings(app: DystopoApp): Pair<List<Holdings.Holding>, Holdings.Online?> {
        val t = app.tiles
        val o = t.online
        val member = o?.onlineMember()
        val areas = runCatching { io.github.jasondorjeshort.dystopo.app.areas.Areas.lookupHoldings() }.getOrDefault(emptyList())
        val cache = if (o == null || member == null) null else Holdings.Holding(
            "cache", Holdings.Kind.CACHE, Holdings.Stored(0, member.storedMaxZoom), Holdings.Stored(0, member.demMaxZoom ?: OnlineTiles.DEM_MAX_ZOOM),
            VectorProfile.OPENMAPTILES,
            cells = { role, z, x, y -> if (role == Holdings.Role.VECTOR) o.fetcher.hasCached("osm", "$z/$x/$y.pbf") else o.fetcher.hasCached("dem", "$z/$x/$y.png") },
        )
        return (t.packs.holdings() + areas + listOfNotNull(cache)) to member
    }

    /** T354: [HoldingsHere] at ([lat], [lon]) and [zoom]. */
    fun holdingsHere(app: DystopoApp, zoom: Double, lat: Double, lon: Double): HoldingsHere {
        val (all, member) = holdings(app)
        val vz = floor(zoom).toInt().coerceAtLeast(0)
        val dz = (floor(zoom).toInt() + 1).coerceIn(0, OnlineTiles.DEM_MAX_ZOOM)
        val now = all.filter { !it.off && (it.kind == Holdings.Kind.MAP || it.kind == Holdings.Kind.OVERVIEW) }
        val vector = Holdings.point(Holdings.Role.VECTOR, lat, lon, vz, all, member)
        val vectorNow = Holdings.point(Holdings.Role.VECTOR, lat, lon, vz, now, member)
        val overview = all.firstOrNull { it.kind == Holdings.Kind.OVERVIEW }?.vector?.let { Holdings.Overview(it.minZoom, it.maxZoom) }
        val rule = Holdings.vectorTile(vz, vectorNow.serving?.holding?.kind == Holdings.Kind.MAP, overview, member, vector.members.any { it.holding.kind == Holdings.Kind.CACHE })
        return HoldingsHere(
            zoom, lat, lon, all, member, vector, Holdings.point(Holdings.Role.DEM, lat, lon, dz, all, member), vectorNow,
            Holdings.point(Holdings.Role.DEM, lat, lon, dz, now, member), rule, Holdings.readZoom(all, member, lat, lon, ShadeTiles.POINT_ZOOM),
        )
    }

    /** `GET /holdings[?z=&lat=&lon=]` (default: the camera's zoom and centre). */
    private fun holdingsRoute(app: DystopoApp, r: Request): Response {
        val cam = runCatching { app.map.onMain(5_000) { app.map.map?.cameraPosition } }.getOrNull()
        val z = r.query["z"]?.toDoubleOrNull() ?: cam?.zoom ?: return bad("no camera; give z, lat and lon")
        val lat = r.query["lat"]?.toDoubleOrNull() ?: cam?.target?.latitude ?: return bad("no camera; give lat")
        val lon = r.query["lon"]?.toDoubleOrNull() ?: cam?.target?.longitude ?: return bad("no camera; give lon")
        return Response.json(holdingsHere(app, z, lat, lon).json())
    }
    // end T354

    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.method == "GET" && r.path == "/holdings") return holdingsRoute(app, r) // T354
        if (r.path != "/packs" && !r.path.startsWith("/packs/")) return null
        val m = PackManager.get(app)
        return try {
            when {
                r.method == "GET" && r.path == "/packs" -> Response.json(m.json() + ("notOpened" to app.tiles.packs.notOpened.map { it.json() })) // T352
                r.method == "POST" && r.path == "/packs/activate" -> Response.json(m.activate(r.query["name"] ?: return bad("name required")))
                r.method == "POST" && r.path == "/packs/download" -> {
                    val url = r.query["url"] ?: r.bodyText.trim().takeIf { it.startsWith("http") } ?: return bad("url required")
                    val job = m.downloads.start(url, r.query["name"], activate = r.query["activate"] == "true")
                    Response.json(job.json())
                }
                r.method == "GET" && r.path == "/packs/download" -> Response.json(m.downloads.json())
                r.method == "POST" && r.path == "/packs/download/retry" -> Response.json(m.downloads.retry(r.query["name"] ?: return bad("name required")).json()) // T222
                r.method == "POST" && r.path == "/packs/region/retry" -> Response.json(io.github.jasondorjeshort.dystopo.app.online.OnlineService.get(app).region.retry()) // T222
                r.method == "POST" && r.path == "/packs/download/free" -> { // T222
                    m.downloads.freeOverride = r.query["bytes"]?.toLongOrNull()
                    Response.json(m.downloads.json())
                }
                r.method == "POST" && r.path == "/packs/delete" -> Response.json(m.delete(r.query["name"] ?: return bad("name required")))
                r.method == "POST" && r.path == "/packs/ui" -> {
                    val show = r.query["show"] != "false"
                    fun flag(k: String) = r.query[k]?.let { it == "1" || it == "true" }
                    val catalog = flag("catalog")
                    val fold = flag("fold")
                    val answer = app.map.onMain {
                        PacksUi.visible = show
                        if (catalog != null) PacksUi.catalogOpen = catalog
                        if (catalog == true) PacksUi.fitRequest++
                        if (fold != null) { PacksUi.foldOpen = fold; if (!fold) PacksUi.catalogOpen = false } // T235
                        if (flag("full") == true) { PacksUi.catalogOpen = false; PacksUi.lowered = false } // T235: "Full list"
                        r.query["detail"]?.let { d -> // T222; T235: a row of the one list opens as its tap does
                            val row = d.replace(Regex("^job:"), io.github.jasondorjeshort.dystopo.app.areas.OfflineMapList.PACK)
                            when {
                                d.isEmpty() -> { PacksUi.detail = null; io.github.jasondorjeshort.dystopo.app.areas.Areas.detailOpen = emptySet() }
                                d.startsWith("cat:") -> PacksUi.detail = d
                                row.startsWith(io.github.jasondorjeshort.dystopo.app.areas.OfflineMapList.AREA) -> io.github.jasondorjeshort.dystopo.app.areas.Areas.detailOpen += row.removePrefix(io.github.jasondorjeshort.dystopo.app.areas.OfflineMapList.AREA)
                                else -> io.github.jasondorjeshort.dystopo.app.areas.Areas.detailOpen += row
                            }
                        }
                        linkedMapOf(
                            "visible" to show, "detail" to PacksUi.detail, "rowDetail" to io.github.jasondorjeshort.dystopo.app.areas.Areas.detailOpen.toList(),
                            "catalogOpen" to PacksUi.catalogOpen, "foldOpen" to PacksUi.foldOpen, "height" to (if (PacksUi.catalogOpen || PacksUi.lowered) "lower" else "full"),
                        )
                    }
                    Response.json(answer + mapOf("firstVisibleItem" to PacksUi.firstVisibleItem, "localRows" to PacksUi.localRows)) // T57
                }
                // T53: the server's catalog
                r.method == "GET" && r.path == "/packs/catalog" -> {
                    val c = m.catalogClient
                    val server = r.query["server"]
                    val error = runCatching {
                        if (r.query["refresh"] == "1" || server != null || c.catalog == null) c.refresh(server) else c.current()
                    }.exceptionOrNull()
                    val body = c.json()
                    Response.json(if (error != null) body + mapOf("error" to (error.message ?: error.javaClass.simpleName)) else body, if (error != null && c.catalog == null) 502 else 200)
                }
                r.method == "POST" && r.path == "/packs/catalog/refresh" -> { // T57
                    val c = m.catalogClient
                    val error = runCatching { c.refresh(r.query["server"]) }.exceptionOrNull()
                    app.map.onMain { PacksUi.catalogRefresh++ }
                    val body = c.json() + mapOf("refreshed" to (error == null), "fetchedAt" to c.fetchedAt)
                    Response.json(if (error != null) body + mapOf("error" to (error.message ?: error.javaClass.simpleName)) else body, if (error != null) 502 else 200)
                }
                r.method == "POST" && r.path == "/packs/catalog/download" -> {
                    val id = r.query["id"] ?: r.query["name"] ?: return bad("id required")
                    Response.json(m.catalogClient.download(id, activate = r.query["activate"] == "true").json())
                }
                r.method == "GET" && r.path == "/packs/update" -> {
                    val rows = m.catalogClient.updates()
                    Response.json(mapOf("count" to rows.size, "updates" to rows.map { it.json() }))
                }
                r.method == "POST" && r.path == "/packs/update" -> Response.json(m.catalogClient.update(r.query["name"] ?: return bad("name required")).json())
                r.method == "DELETE" -> packPath.matchEntire(r.path)?.let { Response.json(m.delete(it.groupValues[1])) } ?: Response.notFound()
                else -> Response.notFound()
            }
        } catch (e: NoSuchElementException) {
            Response.json(mapOf("error" to e.message), 404)
        } catch (e: IllegalArgumentException) {
            bad(e.message ?: "bad request")
        } catch (e: java.io.IOException) {
            Response.json(mapOf("error" to (e.message ?: e.javaClass.simpleName)), 502) // T53: the catalog server
        }
    }

    private fun bad(message: String) = Response.json(mapOf("error" to message), 400)
}
