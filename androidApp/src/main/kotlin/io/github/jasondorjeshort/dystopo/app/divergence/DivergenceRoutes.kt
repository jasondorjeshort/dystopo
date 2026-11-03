package io.github.jasondorjeshort.dystopo.app.divergence

import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.BuildConfig
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.app.tracking.LongPressMenu

/**
 * T213 trail divergence debug routes (debug builds only, behind the loopback token; registered from
 * [io.github.jasondorjeshort.dystopo.app.Routes]):
 * - `GET /divergence`: the setting (`showDivergence`), `running`, `pending` (a run waiting for the foreground: T150),
 *   `runs`; `records` (confirmed) and `unconfirmed`, each with `id`, `confirmedBy` (`days` or `accuracy`), `tracks`,
 *   `traversals`, `days`, `lengthM`, `medianOffsetM`, `mid`, `edge` (the nearest mapped line at the midpoint: where,
 *   `distanceM`, `name`, `class`), `trackIds`, `starts`, `line` (`[lat, lon]` pairs), `shown`, `dismissed` and `strings`
 *   (the card's title, body in the units preference, Dismiss); `shown` (how many the map draws), `dismissedCount`,
 *   `dismissed`; `lastRun` (`at`, `why`, `ms`, `tracks`, `read`, `cached`, `candidates`, `confirmed`, `unconfirmed`,
 *   `shown`, or `error`); `card` (the open long-press card: `open`, `radiusM`, and the stretch it shows: `id`, `title`,
 *   `body`); `layer` (`installedOnStyle`, `stretches`, `updates`); `thresholds`.
 * - `POST /divergence/run[?force=1][&wait=60000]`: runs the detector now on the request's thread over the stored tracks
 *   (whatever the setting; `force` reads every track again rather than the cached candidates), waiting up to `wait` ms
 *   (default 60 s) for the trail network; answers like `GET /divergence`.
 * - `POST /divergence/dismiss?id=`: the card's Dismiss; 404 for an unknown id.
 * - `POST /divergence/undismiss`: every dismissed stretch shows again (`undismissed`: how many).
 */
object DivergenceRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        if (!r.path.startsWith("/divergence") || !BuildConfig.DEBUG_ENDPOINTS) return null
        return when (r.method to r.path) {
            "GET" to "/divergence" -> Response.json(json(app))
            "POST" to "/divergence/run" -> {
                val wait = r.query["wait"]?.let { it.toLongOrNull()?.takeIf { w -> w in 0..300_000 } ?: return Response.json(mapOf("error" to "wait must be 0 to 300000 ms"), 400) }
                val force = r.query["force"] == "1"
                val run = DivergenceStore.run(app, "route", force, wait ?: DivergenceStore.GRAPH_WAIT_MS)
                settle(app)
                Response.json(json(app) + mapOf("run" to run))
            }
            "POST" to "/divergence/dismiss" -> {
                val id = r.query["id"] ?: return Response.json(mapOf("error" to "id required"), 400)
                if (!DivergenceStore.dismiss(id)) return Response.json(mapOf("error" to "no such stretch", "id" to id), 404)
                settle(app)
                Response.json(json(app))
            }
            "POST" to "/divergence/undismiss" -> {
                val n = DivergenceStore.undismissAll()
                settle(app)
                Response.json(json(app) + mapOf("undismissed" to n))
            }
            else -> Response.json(mapOf("error" to "unknown divergence route"), 404)
        }
    }

    private fun json(app: DystopoApp): Map<String, Any?> {
        val point = LongPressMenu.point
        val radius = point?.let { p -> runCatching { app.map.onMain { pressRadiusM(p.first) } }.getOrNull() } ?: PRESS_MIN_M
        return DivergenceStore.json(AppSettings.units, point, radius)
    }

    /** Lets the posted layer update and the card recompose before the answer. */
    private fun settle(app: DystopoApp) {
        runCatching { app.map.onMain { } }
        Thread.sleep(100)
    }
}
