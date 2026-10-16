package io.github.jasondorjeshort.dystopo.app.update

import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import java.util.concurrent.TimeUnit

/**
 * T83 debug routes (debug builds only, on the debug port). Debug builds never check by themselves (a device tour
 * must not grow an update banner); these routes drive the same code the release build runs daily:
 * - `GET /update`: state (`phase`: idle, checking, up_to_date, downloading, ready, installing, installed, declined,
 *   refused, failed; `message` is what Settings shows), this build's version and signer fingerprints, the API in
 *   use and where it came from (`default`, `file` = `update-api.txt` in the external files dir, `debug`), the
 *   declined, refused and ready tags, `canRequestInstalls`.
 * - `POST /update/check[?api=<releases URL>|default][&wait=0]`: a manual check ("Check for updates"); waits for
 *   the result (download and verification included) unless `wait=0`. `api=` is kept for later checks.
 * - `POST /update/install[?now=1]`: hands the verified APK to the system installer (the confirmation screen opens).
 *   T110: while navigating it waits for the session to end instead (202, `waitingForNavigation`; it installs by itself
 *   once navigation stops), unless `now=1` (the offer's "Install now"). `GET /update` adds `waitingForNavigation`,
 *   `installsAfterNavigation`, `navigating` and `overlay` (the offer on the map: `shown` or `hiddenBecause`,
 *   `duringNavigation`, `topDp`, `maxHeightDp`, `navCardBottomDp`, `clearOfNavCard`).
 * - `POST /update/dismiss`: "Not now" (declines that version); `POST /update/ack`: "OK" on a refusal.
 * - `POST /update/reset`: forgets declined, refused and ready versions, the download and the last check time.
 */
object UpdateRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/update" && !r.path.startsWith("/update/")) return null
        if (!Updater.initialized()) Updater.start(app)
        return when (r.method to r.path) {
            "GET" to "/update" -> Response.json(Updater.json())
            "POST" to "/update/check" -> {
                r.query["api"]?.let { api -> Updater.setDebugApi(api.toString())?.let { return Response.json(mapOf("error" to it), 400) } }
                val f = Updater.check(manual = true)
                if (r.query["wait"]?.toString() != "0") runCatching { f.get(10, TimeUnit.MINUTES) }
                Response.json(Updater.json())
            }
            "POST" to "/update/install" -> {
                val problem = Updater.install(now = r.query["now"] == "1")
                Response.json(Updater.json() + mapOf("error" to problem), if (problem != null) 409 else if (Updater.waitingForNavigation) 202 else 200)
            }
            "POST" to "/update/dismiss" -> { Updater.decline(); Response.json(Updater.json()) }
            "POST" to "/update/ack" -> { Updater.acknowledge(); Response.json(Updater.json()) }
            "POST" to "/update/reset" -> { Updater.reset(); Response.json(Updater.json()) }
            else -> Response.notFound()
        }
    }
}
