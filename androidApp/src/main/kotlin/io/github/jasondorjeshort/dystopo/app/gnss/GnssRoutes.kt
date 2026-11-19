package io.github.jasondorjeshort.dystopo.app.gnss

import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.gnss.GnssCases

/**
 * T307: the GPS view's debug routes (debug builds, behind the loopback token):
 * - `GET /gnss`: the view (`open`, `drawn`: what the screen last drew: satellites, used, behind the terrain, on a second
 *   band, the plot's `rotationDeg` and `orientation`, `followsHeading`, `horizonDrawn`, `sunDrawn`, `night`), the GNSS
 *   (`holders`, `engine`, `error`, `permission`, `source`: `device` or `injected:<case>`, `statusCallbacks`, `fixes`,
 *   `statusAgeMs`), `summary` (`inView`, `used`, `best4`, `best4Mean`, `constellations`, `bands`, `bandLine`,
 *   `accuracyM`, `fixAgeMs`, `ttffMs`: the app's own first fix, `engineTtffMs`), `bandLine` (as the view words it),
 *   `satellites` (the list's order, each with `behindTerrain`), `horizon` (`source`: `offline map` or `online`, `zoom`,
 *   `ms`, `stepDeg`, `eyeHeightM`, `groundM`, `highestDeg`, `highestBearingDeg`, `highestDistanceM`, `angleDeg`: one per
 *   degree from true north) or null with `horizonReason`, `orientation`, `headingDeg`, `check` (`state`, `seconds`,
 *   `elapsedS`, `secondsLeft`, `last` with `verdict`, `headline`, `advice`, `figures`, or `stopped`), `lastReset`. No position.
 * - `POST /gnss?case=open|weak|fault|indoors|ridge`: a synthetic status ([GnssCases]) in place of the phone's (`ridge`
 *   waits up to `wait` ms, default 30000, for the terrain horizon at the phone's position and puts its weak satellites
 *   behind it); `POST /gnss?clear=1` returns to the phone's own. Answers `GET /gnss`; 400 for an unknown case.
 * - `POST /gnss/ui?open=1|0`: opens or closes the GPS view, as the settings row and Close do; answers `GET /gnss`.
 * - `POST /gnss/check?seconds=N` (default 120, 1 to 600): starts "Check my GPS" (409 while one runs); `&wait=1` answers
 *   when it has finished; `POST /gnss/check?stop=1` stops it. Answers the check's JSON.
 * - `POST /gnss/reset`: "Reset assistance data" without the confirmation; `taken` false when the phone refused it.
 */
object GnssRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/gnss" && !r.path.startsWith("/gnss/")) return null
        return when (r.method to r.path) {
            "GET" to "/gnss" -> Response.json(Gnss.json())
            "POST" to "/gnss" -> {
                if (r.query["clear"] == "1") {
                    Gnss.clear()
                    return Response.json(Gnss.json())
                }
                val case = r.query["case"] ?: return Response.json(mapOf("error" to "case or clear=1 required", "cases" to GnssCases.NAMES), 400)
                if (case !in GnssCases.NAMES) return Response.json(mapOf("error" to "unknown case $case", "cases" to GnssCases.NAMES), 400)
                if (case == GnssCases.RIDGE) Gnss.awaitHorizon(r.query["wait"]?.toLongOrNull() ?: 30_000L)
                Gnss.inject(case)
                Response.json(Gnss.json())
            }
            "POST" to "/gnss/ui" -> {
                when (r.query["open"]) {
                    "1", "true" -> app.map.onMain { GnssUi.open() }
                    "0", "false" -> app.map.onMain { GnssUi.close() }
                    else -> return Response.json(mapOf("error" to "open=1 or open=0"), 400)
                }
                Thread.sleep(600) // the composition and the first second's reading
                Response.json(Gnss.json())
            }
            "POST" to "/gnss/check" -> {
                if (r.query["stop"] == "1") {
                    GnssCheck.stop()
                    return Response.json(GnssCheck.json())
                }
                val seconds = r.query["seconds"]?.let { it.toIntOrNull()?.takeIf { s -> s in 1..600 } ?: return Response.json(mapOf("error" to "seconds must be 1 to 600"), 400) } ?: io.github.jasondorjeshort.dystopo.gnss.GnssHealth.CHECK_SECONDS
                if (!GnssCheck.start(seconds)) return Response.json(mapOf("error" to "a check is running") + GnssCheck.json(), 409)
                if (r.query["wait"] == "1") {
                    val until = System.currentTimeMillis() + (seconds + 40) * 1000L
                    while (GnssCheck.state == GnssCheck.State.RUNNING && System.currentTimeMillis() < until) Thread.sleep(250)
                }
                Response.json(GnssCheck.json())
            }
            "POST" to "/gnss/reset" -> Response.json(mapOf("taken" to Gnss.resetAssistance()))
            else -> null
        }
    }
}
