package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.sync.AppSync
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.sync.EntityKind

/**
 * Debug routes for sync (T44), consulted by [Routes] (debug builds only):
 * - `POST /sync?with=<url|file>`: one sync (pull, merge, push) with an HTTP peer
 *   (`http://10.0.2.2:8775`, a devtool `serve`) or a `.dsync` file in `files/sync/` (a plain name;
 *   created when missing, merged and rewritten when present). Without `with`, the default peer (the
 *   `peer` setting, else the online server), as the "Sync now" action in the tracks list does.
 *   JSON: pulled, merged counts (applied, duplicate, dead, gap devices), pushed, both vectors, ms.
 * - `GET /sync/status`: device id, vector, operation count, state counts and digest (equal digests on
 *   two replicas mean equal synced data), whether the tables match the log, peer, last result, files.
 * - `GET /sync/settings`; `POST /sync/settings?peer=<url|file|>`: the default peer (empty: use the online server).
 * - `POST /sync/field?kind=track|waypoint|route&id=&field=name&value=`: change one synced field (a
 *   rename, for checking concurrent edits; omit `value` to clear a text field).
 */
object SyncRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/sync" && !r.path.startsWith("/sync/")) return null
        val sync = AppSync.of(app)
        return try {
            when (r.method to r.path) {
                "POST" to "/sync" -> Response.json(sync.syncNow(r.query["with"]))
                "GET" to "/sync/status", "GET" to "/sync" -> Response.json(sync.status())
                "GET" to "/sync/settings" -> Response.json(mapOf("peer" to sync.peerSetting, "defaultPeer" to sync.defaultPeer))
                "POST" to "/sync/settings" -> {
                    val peer = r.query["peer"] ?: return Response.json(mapOf("error" to "pass peer= (empty to clear)"), 400)
                    sync.setPeer(peer)
                    Response.json(mapOf("peer" to sync.peerSetting, "defaultPeer" to sync.defaultPeer))
                }
                "POST" to "/sync/field" -> {
                    val kind = when (r.query["kind"]) {
                        "track" -> EntityKind.TRACK
                        "waypoint" -> EntityKind.WAYPOINT
                        "route" -> EntityKind.ROUTE
                        else -> return Response.json(mapOf("error" to "kind must be track, waypoint or route"), 400)
                    }
                    val id = r.query["id"] ?: return Response.json(mapOf("error" to "pass id="), 400)
                    val field = r.query["field"] ?: "name"
                    val t = Tracking.of(app)
                    val ok = t.call { (t.store as io.github.jasondorjeshort.dystopo.sync.SyncStore).setField(kind, id, field, r.query["value"], System.currentTimeMillis()) }
                    if (!ok) return Response.json(mapOf("error" to "no $kind $id or unknown field $field (fields: ${kind.fields.sorted()})"), 404)
                    t.refreshUi()
                    t.refreshOverlay(force = true)
                    Response.json(mapOf("kind" to kind.name.lowercase(), "id" to id, "field" to field, "value" to r.query["value"]))
                }
                else -> Response.json(mapOf("error" to "POST /sync?with=, GET /sync/status, GET|POST /sync/settings, POST /sync/field"), 404)
            }
        } catch (e: IllegalArgumentException) {
            Response.json(mapOf("error" to e.message), 400)
        } catch (e: Exception) {
            Response.json(mapOf("error" to "${e.javaClass.simpleName}: ${e.message}"), 502)
        }
    }
}
