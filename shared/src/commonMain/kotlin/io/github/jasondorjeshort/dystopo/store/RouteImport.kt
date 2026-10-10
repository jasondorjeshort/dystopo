package io.github.jasondorjeshort.dystopo.store

import io.github.jasondorjeshort.dystopo.gpx.Gpx
import io.github.jasondorjeshort.dystopo.gpx.GpxException
import io.github.jasondorjeshort.dystopo.model.RouteGpx
import io.github.jasondorjeshort.dystopo.model.RoutePlan
import io.github.jasondorjeshort.dystopo.model.UuidGenerator
import io.github.jasondorjeshort.dystopo.sync.SyncStore

/**
 * GPX routes into a [SyncStore] (T60), the counterpart of [TrackImport] for `rte`:
 * - Every `rte` becomes a planned route ([RouteGpx.plan]) with the id from its `dystopo:id` extension
 *   (our own exports) or a fresh UUID. A route whose id is stored already is skipped (importing an
 *   export of this store changes nothing), unless [replace] is set.
 * - The file's `wpt`s belong to the route when the file holds exactly one route and no track (that is
 *   how [RouteGpx] writes a route); otherwise they are not imported here ([TrackImport] takes them).
 * - Name: the route's, else the file's metadata name, else [fallbackName].
 */
object RouteImport {
    data class Result(val imported: List<RoutePlan>, val skipped: List<String>, val waypoints: Int, val ignored: Map<String, Int>)

    /** Throws [GpxException] for a file that isn't GPX or holds no route. */
    fun import(store: SyncStore, gpx: String, now: Long, fallbackName: String = "Imported route", replace: Boolean = false, ids: UuidGenerator = UuidGenerator()): Result {
        val parsed = Gpx.read(gpx, ids)
        val doc = parsed.document
        if (doc.routes.isEmpty()) throw GpxException("no routes")
        val attach = doc.routes.size == 1 && doc.tracks.isEmpty()
        val imported = ArrayList<RoutePlan>()
        val skipped = ArrayList<String>()
        var waypoints = 0
        for (r in doc.routes) {
            if (!replace && store.routePlan(r.id) != null) {
                skipped += r.id
                continue
            }
            val name = r.name.ifBlank { doc.metadata?.name?.takeIf { it.isNotBlank() } ?: fallbackName }
            val plan = RouteGpx.plan(r.copy(name = name), if (attach) doc.waypoints else emptyList())
            store.putRoutePlan(plan, now)
            imported += plan
            waypoints += plan.waypoints.size
        }
        return Result(imported, skipped, waypoints, parsed.ignored)
    }
}
