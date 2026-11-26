package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.search.MapCoverage
import io.github.jasondorjeshort.dystopo.app.search.PlaceSearch
import io.github.jasondorjeshort.dystopo.app.search.SearchMarker
import io.github.jasondorjeshort.dystopo.app.search.SearchUi
import io.github.jasondorjeshort.dystopo.search.PlaceKind

/**
 * Debug routes for place search (T39; registered with one line in [Routes]):
 * - `GET /search?q=&lat=&lon=[&limit=][&kind=peak,hut]`: hits from every pack's index (T41; each hit names its `pack`), ranked
 *   around lat/lon (default: the camera centre). 503 while the index is still loading or building. T255: `reason`, why the
 *   hits are what they are at lat/lon: `found` and `no-match` where an offline map with a vector archive covers it,
 *   `tiles-only` where only map tiles draw there (viewed earlier, a map-only download, cut from a wider zoom, a terrain-only
 *   map: no index there; hits of other maps still listed), `no-map` where nothing on the phone does; `coverage` (`vector`,
 *   `tiles-only`, `no-map`) and `sentence`, the search box's line for it (null when the hits speak for themselves).
 * - `GET /search/status`: index state per pack on the map (status, build progress, timings, entries). T149: it only reads
 *   (as `GET /packs` `search` does): a pack whose index nobody asked for yet is `idle`, and nothing is loaded or built
 *   until a use (`GET /search`, `POST /search/select`, `POST /search/build`, or the search box open or holding text,
 *   `ui.wanted`); per pack `guard` (the heap guard's admission of the build), `heapStartBytes`, `peakHeapBytes`; states
 *   `waiting` (for the guard) and `refused`. T255: `ui.coverage`, the camera centre's as the box last read it while wanted
 *   (`vector`, `tiles-only`, `no-map`, null), and `ui.offerOfflineMaps`, the box shows the Offline maps link under its line.
 *   T377: the hits come from the areas kept as tiles too (a downloaded area with no pack: each hit's `pack` is then the
 *   area's name), and `coverage` is search's own: `vector` also where such an area covers lat/lon, while `routeCoverage` is
 *   what a route there reads (`tiles-only` inside such an area: routes need an offline map); `searchFrom` `map`, `area` or
 *   null says which; `reason` `indexing` (and the box's line as `sentence`) while that area's first index is being built.
 *   `GET /search/status` `index.areas`: one entry per area kept as tiles, as a pack's (`status`, `progress`, `buildMs`,
 *   `loadMs`, `entries`, `bytes` of the index file, `guard`, `peakHeapBytes`, `rebuildReason`) with `kind` `area`, `areaId`,
 *   `versions`, `zooms` scanned and `deepestZoom` (13 for an area at Less detail, `lessDetail` true: it has no z14 and
 *   finds fewer places), `tilesPlanned`, `tilesPresent` and `tilesMissing` at those zooms (`plannedByZoom`, `missingByZoom`;
 *   a download skips a tile an open map answers), `scanMs`, `fingerprint`, `checks` and `checkedAgoMs` (a use counts the
 *   tiles again after a minute; a change rebuilds the index); `index.orphansSwept`, index files deleted because their area
 *   was gone. An area's index is built on use only, its file beside the area's manifest (`files/areas/<id>-search.idx`).
 * - `POST /search/build`: rebuild every pack index (progress in `/search/status` and `/packs`); T377: and every area's.
 * - `POST /search/ui?q=`: type [q] into the search box (opens the result list), as a user would. T149: `?focus=1|0`
 *   gives the text field the focus or takes it away, as a tap in the box or Back does (`ui.focused`).
 * - `POST /search/select?q=[&i=0]`: choose result i for q, as a tap does: camera move and marker.
 * - `POST /search/clear`: clear the box, the results and the marker.
 */
object SearchRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/search" && !r.path.startsWith("/search/")) return null
        return when (r.method to r.path) {
            "GET" to "/search" -> {
                val q = r.query["q"] ?: return bad("q required")
                val kinds = r.query["kind"]?.split(',')?.map { PlaceKind.ofLabel(it.trim()) ?: return bad("unknown kind '$it'") }?.toSet()
                val limit = r.query["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 20
                val (lat, lon) = center(app, r) ?: return bad("lat and lon must be numbers")
                val t0 = System.nanoTime()
                val hits = PlaceSearch.search(q, lat, lon, limit, kinds)
                    ?: return Response.json(mapOf("error" to "search index not ready", "index" to PlaceSearch.json(app)), 503)
                val queryMs = (System.nanoTime() - t0) / 10_000 / 100.0
                // T255: why, at the centre (the view's source read now, at the camera's zoom).
                val zoom = runCatching { app.map.onMain { app.map.map?.cameraPosition?.zoom } }.getOrNull() ?: 12.0
                val route = if (lat != null && lon != null) PlaceSearch.coverage(app, lat, lon, zoom) else null
                // T377: search's own coverage: an area kept as tiles covering the centre is searchable, never routable.
                val area = if (lat != null && lon != null) PlaceSearch.areaAt(app, lat, lon) else null
                val cov = route?.let { MapCoverage.forSearch(it, area != null) }
                val indexing = area != null && route != MapCoverage.VECTOR && area.index == null
                Response.json(linkedMapOf(
                    "query" to q, "center" to listOf(lat, lon), "queryMs" to queryMs,
                    "count" to hits.size, "hits" to hits.map { it.json() },
                    "reason" to cov?.let { MapCoverage.searchReason(it, hits.size, indexing) }, "coverage" to cov?.code,
                    "routeCoverage" to route?.code, "searchFrom" to when { route == MapCoverage.VECTOR -> "map"; area != null -> "area"; else -> null },
                    "area" to area?.let { mapOf("name" to it.pack, "id" to it.area?.id, "status" to it.status) },
                    "sentence" to cov?.let { if (indexing && hits.isEmpty()) io.github.jasondorjeshort.dystopo.app.search.areaIndexingLine(area!!.pack, area.done, area.total) else MapCoverage.searchSentence(it, hits.size) },
                ))
            }
            "GET" to "/search/status" -> Response.json(status(app))
            "POST" to "/search/build" -> Response.json(PlaceSearch.ensure(app, rebuild = true).takeIf { it.isNotEmpty() }?.let { l -> mapOf("packs" to l.map { it.json() }) } ?: mapOf("error" to "no pack on the map has a vector archive, and no area is kept as tiles"))
            "POST" to "/search/ui" -> {
                val focus = r.query["focus"]
                app.map.onMain {
                    if (focus != null) SearchUi.focused = focus == "1" // T149: the box opened (or closed) without typing
                    if (focus == null || r.query.containsKey("q")) r.query["q"].orEmpty().let { q -> SearchUi.query = q; SearchUi.open = q.isNotBlank() }
                    SearchUi.refresh()
                }
                Response.json(status(app))
            }
            "POST" to "/search/select" -> {
                val q = r.query["q"] ?: return bad("q required")
                val i = r.query["i"]?.toIntOrNull() ?: 0
                val (lat, lon) = center(app, r) ?: return bad("lat and lon must be numbers")
                val hits = PlaceSearch.search(q, lat, lon, maxOf(i + 1, 8))
                    ?: return Response.json(mapOf("error" to "search index not ready", "index" to PlaceSearch.json(app)), 503)
                val hit = hits.getOrNull(i) ?: return Response.json(mapOf("error" to "no result $i for '$q'", "count" to hits.size), 404)
                app.map.onMain { SearchUi.query = q; SearchUi.refresh() }
                Response.json(SearchUi.select(hit))
            }
            "POST" to "/search/clear" -> {
                app.map.onMain { SearchUi.clear() }
                Response.json(status(app))
            }
            else -> Response.notFound()
        }
    }

    private fun status(app: DystopoApp): Map<String, Any?> {
        val ui = app.map.onMain {
            linkedMapOf(
                "query" to SearchUi.query, "open" to SearchUi.open, "status" to SearchUi.status,
                "focused" to SearchUi.focused, "wanted" to SearchUi.wanted, // T149
                "coverage" to SearchUi.coverage?.code, // T255
                "offerOfflineMaps" to (SearchUi.coverage == MapCoverage.TILES_ONLY && SearchUi.status == io.github.jasondorjeshort.dystopo.app.search.SEARCH_TILES_ONLY),
                "hits" to SearchUi.hits.map { it.hit.entry.name }, "selected" to SearchUi.selected?.json(),
                "markerInstalled" to SearchMarker.installed(app.map.map?.style),
                "labelInstalled" to SearchMarker.labelInstalled(app.map.map?.style), // T41
            )
        }
        return linkedMapOf("index" to PlaceSearch.json(app), "ui" to ui)
    }

    /** lat/lon from the query, else the camera centre; null when given but not numbers. */
    private fun center(app: DystopoApp, r: Request): Pair<Double?, Double?>? {
        val lat = r.query["lat"]
        val lon = r.query["lon"]
        if (lat != null || lon != null) {
            val a = lat?.toDoubleOrNull() ?: return null
            val b = lon?.toDoubleOrNull() ?: return null
            return a to b
        }
        val c = runCatching { app.map.onMain { app.map.map?.cameraPosition?.target } }.getOrNull()
        return c?.latitude to c?.longitude
    }

    private fun bad(message: String) = Response.json(mapOf("error" to message), 400)
}
