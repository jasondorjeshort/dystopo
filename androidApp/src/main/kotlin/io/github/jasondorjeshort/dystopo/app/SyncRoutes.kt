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
 * - `GET /sync/settings`; `POST /sync/settings?peer=<url|file|>[&token=]`: the default peer (empty: use the online
 *   server) and (T51) the bearer token sent to a gated HTTP peer (the `server` module's `/sync`; empty clears it).
 * - `POST /sync/field?kind=track|waypoint|route&id=&field=name&value=`: change one synced field (a
 *   rename, for checking concurrent edits; omit `value` to clear a text field).
 *
 * T51: `POST /sync` pages (`&page=N` operations per page, default 2000; `page=0` for one bundle each
 * way) and reports `pullPages`, `pushPages`, bytes, `conflicts` and the synced settings it applied;
 * `/sync/status` adds `pendingOps`, `conflicts`, `clockSkew` (the skew guard's log), `settingsSync`
 * and `runsExtended`. Also:
 * - `GET /sync/conflicts`: fields edited on two devices (newest first); `DELETE /sync/conflicts[?id=]` dismisses notes.
 * - `POST /sync/ui?open=1|0`: opens or closes the sync status sheet (for screenshots).
 *
 * T158 (ADR 0033 §3): a sync refused because one side cannot read the other's data format (a newer peer's
 * operation types, or an older peer refusing ours) fails `POST /sync` with the readable reason in `error` and
 * `refused: "format"`; `/sync/status` counts them in `refusals` and keeps the last in `last`.
 */
object SyncRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/sync" && !r.path.startsWith("/sync/")) return null
        val sync = AppSync.of(app)
        return try {
            when (r.method to r.path) {
                "POST" to "/sync" -> {
                    val page = r.query["page"]?.let { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return Response.json(mapOf("error" to "page must be a whole number >= 0"), 400) }
                    Response.json(sync.syncNow(r.query["with"], page ?: io.github.jasondorjeshort.dystopo.sync.Sync.DEFAULT_PAGE_OPS))
                }
                "GET" to "/sync/conflicts" -> Response.json(mapOf("total" to sync.conflictsTotal, "conflicts" to sync.conflicts))
                "DELETE" to "/sync/conflicts" -> {
                    sync.dismiss(r.query["id"])
                    Response.json(mapOf("conflicts" to sync.conflicts.size))
                }
                "POST" to "/sync/ui" -> {
                    val open = r.query["open"] != "0"
                    app.map.onMain { io.github.jasondorjeshort.dystopo.app.sync.SyncUi.visible = open; io.github.jasondorjeshort.dystopo.app.sync.SyncUi.refresh() }
                    Response.json(mapOf("open" to open))
                }
                "GET" to "/sync/status", "GET" to "/sync" -> Response.json(sync.status())
                "GET" to "/sync/settings" -> Response.json(mapOf("peer" to sync.peerSetting, "defaultPeer" to sync.defaultPeer, "tokenSet" to sync.syncToken.isNotEmpty()))
                "POST" to "/sync/settings" -> {
                    // T51: token= sets the bearer token for a peer whose /sync is gated (the server module); empty clears it.
                    if (r.query["peer"] == null && r.query["token"] == null) return Response.json(mapOf("error" to "pass peer= and/or token= (empty to clear)"), 400)
                    r.query["peer"]?.let { sync.setPeer(it) }
                    r.query["token"]?.let { sync.setSyncToken(it) }
                    Response.json(mapOf("peer" to sync.peerSetting, "defaultPeer" to sync.defaultPeer, "tokenSet" to sync.syncToken.isNotEmpty()))
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
                else -> Response.json(mapOf("error" to "POST /sync?with=[&page=], GET /sync/status, GET|POST /sync/settings, POST /sync/field, GET|DELETE /sync/conflicts, POST /sync/ui"), 404)
            }
        } catch (e: Exception) {
            val refusal = io.github.jasondorjeshort.dystopo.sync.SyncRefusal.reason(e) // T158: before the generic 400 (a SyncFormatException is one)
            when {
                refusal != null -> Response.json(mapOf("error" to refusal, "refused" to "format", "detail" to e.message), 409)
                e is IllegalArgumentException -> Response.json(mapOf("error" to e.message), 400)
                else -> Response.json(mapOf("error" to "${e.javaClass.simpleName}: ${e.message}"), 502)
            }
        }
    }
}
