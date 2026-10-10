package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.search.PlaceSearch
import io.github.jasondorjeshort.dystopo.app.search.SearchMarker
import io.github.jasondorjeshort.dystopo.app.search.SearchUi
import io.github.jasondorjeshort.dystopo.search.PlaceKind

/**
 * Debug routes for place search (T39; registered with one line in [Routes]):
 * - `GET /search?q=&lat=&lon=[&limit=][&kind=peak,hut]`: hits from the active pack's index, ranked
 *   around lat/lon (default: the camera centre). 503 while the index is still loading or building.
 * - `GET /search/status`: index state for the active pack (status, build progress, timings, entries).
 * - `POST /search/build`: rebuild the active pack's index (progress in `/search/status` and `/packs`).
 * - `POST /search/ui?q=`: type [q] into the search box (opens the result list), as a user would.
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
                Response.json(linkedMapOf(
                    "query" to q, "center" to listOf(lat, lon), "queryMs" to (System.nanoTime() - t0) / 10_000 / 100.0,
                    "count" to hits.size, "hits" to hits.map { it.json() },
                ))
            }
            "GET" to "/search/status" -> Response.json(status(app))
            "POST" to "/search/build" -> Response.json(PlaceSearch.ensure(app, rebuild = true)?.json() ?: mapOf("error" to "active pack has no vector archive"))
            "POST" to "/search/ui" -> {
                val q = r.query["q"].orEmpty()
                app.map.onMain { SearchUi.query = q; SearchUi.open = q.isNotBlank(); SearchUi.refresh() }
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
                "hits" to SearchUi.hits.map { it.entry.name }, "selected" to SearchUi.selected?.json(),
                "markerInstalled" to SearchMarker.installed(app.map.map?.style),
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
