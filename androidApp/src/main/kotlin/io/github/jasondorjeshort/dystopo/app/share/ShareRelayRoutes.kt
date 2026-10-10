package io.github.jasondorjeshort.dystopo.app.share

import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response

/**
 * Debug routes for sync through the server relay (T72; [RelaySession]), consulted from `ShareRoutes`
 * (debug builds only, loopback server):
 * - `POST /share/relay/start[?server=<url>][&code=<code>]`: starts relaying, with a new code or the one
 *   given (the other phone's); one sync at once, then polling every 5 s. JSON as `GET /share/relay`.
 *   `server` defaults to the sync peer when it is a URL, else the online server.
 * - `POST /share/relay/sync`: one more exchange now (JSON as `POST /sync`, plus `received`, `rejected`).
 * - `POST /share/relay/stop[?forget=1]`: stops polling; `forget=1` also deletes this phone's blob there.
 * - `GET /share/relay`: `active`, `code` (and `codeShown`, grouped), `server`, `slot`, `side`, polls,
 *   syncs, blobs `received` and `rejected` (did not open under the code), bytes, `lastResult`, `lastError`.
 */
object ShareRelayRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/share/relay" && !r.path.startsWith("/share/relay/")) return null
        val relay = RelaySession.of(app)
        return when (r.method to r.path) {
            "GET" to "/share/relay", "GET" to "/share/relay/status" -> Response.json(relay.status())
            "POST" to "/share/relay/start" -> {
                val status = relay.start(r.query["server"], r.query["code"])
                Response.json(status, if (status["lastError"] == null) 200 else 502)
            }
            "POST" to "/share/relay/sync" -> Response.json(relay.syncNow())
            "POST" to "/share/relay/stop" -> Response.json(relay.stop(forget = r.query["forget"] == "1"))
            else -> Response.json(mapOf("error" to "POST /share/relay/start[?server=&code=], POST /share/relay/sync, POST /share/relay/stop[?forget=1], GET /share/relay"), 404)
        }
    }
}
