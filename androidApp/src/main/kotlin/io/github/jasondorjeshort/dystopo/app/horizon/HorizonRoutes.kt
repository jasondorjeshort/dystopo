package io.github.jasondorjeshort.dystopo.app.horizon

import android.os.SystemClock
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.app.gnss.Gnss
import io.github.jasondorjeshort.dystopo.horizon.HorizonLayer
import io.github.jasondorjeshort.dystopo.horizon.HorizonLayers
import java.time.LocalDate

/**
 * T308: the Horizon view's debug routes (debug builds, behind the loopback token). The satellites layer keeps T307's
 * `/gnss*` routes (`GnssRoutes.kt`).
 * - `GET /horizon`: `open`, `layers` (`satellites`, `peaks`, `sun`: the three synced settings), `gnss` (`held`,
 *   `holders`, `engine`: Satellites off releases the GNSS), `drawn` (what the screen last drew: `landscape`, `squareDp`,
 *   `windowDp`, `fontScale`, `rotationDeg`, `orientation`, `night`, `horizonDrawn`, `satellitesDrawn`, `peaksDrawn`
 *   (the rim's names), `peaksListed`, `sunPathDrawn` (samples), `sunHourMarks`, `sunNowDrawn`, `selected`, `scrollPx`,
 *   `scrollMaxPx`), `horizon` (as `GET /gnss`'s, without the per-degree angles) or null with `horizonReason`, `peaks`
 *   (`reason`, `ms`, `tilesRead`, `tested`, `visible`, `skyline`, `named`, `sectorDeg`, and each skyline peak's `name`,
 *   `eleM`, `distanceM`, `bearingDeg`, `compass`, `angleDeg`, `sector`, `onRim`), `sun` (`date`, `onTerrain`, the terrain
 *   and flat `…SunriseMs`/`…SunsetMs` with their clock times and UTC, `noonMs`, `sunMinutes`, `alwaysUp`, `sunlit`,
 *   `gaps`, `pathSamples`, `pathClear`, `highestDeg`, `hourMarks`; `nowAltitudeDeg`, `nowAzimuthDeg`). No position.
 *   `date=YYYY-MM-DD` answers for that day (the screen keeps its own); `wait=<ms>` waits that long for the horizon and the
 *   peaks; `compare=1` adds `turnaround`: the turn-around clock's terrain day for the same point and day
 *   (`ShadeService.terrainAt`, waited up to 90 s) with `sunsetAgrees` and `sunriseAgrees`.
 * - `POST /horizon?layers=satellites,peaks,sun|all|none` or `satellites=1|0`, `peaks=`, `sun=`: the toggles, as a tap
 *   sets them (stored, synced); `date=YYYY-MM-DD|today`: the day the Sun layer shows; `select=<peak name>|none`: picks a
 *   skyline peak as a tap does; `scroll=<px>`: scrolls the control area. Answers `GET /horizon` (with `wait`).
 * - `POST /horizon/ui?open=1|0`: opens or closes the Horizon view, as the settings row and Close do; answers `GET /horizon`.
 */
object HorizonRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/horizon" && !r.path.startsWith("/horizon/")) return null
        return when (r.method to r.path) {
            "GET" to "/horizon" -> answer(app, r)
            "POST" to "/horizon" -> {
                val q = r.query
                val set = LinkedHashMap<HorizonLayer, Boolean>()
                q["layers"]?.let { spec ->
                    val l = HorizonLayers.parse(spec) ?: return Response.json(mapOf("error" to "layers must be satellites, peaks and sun, comma-separated, or all or none"), 400)
                    for (layer in HorizonLayer.entries) set[layer] = l[layer]
                }
                for (layer in HorizonLayer.entries) q[layer.key]?.let { v ->
                    set[layer] = when (v) {
                        "1", "true", "on" -> true
                        "0", "false", "off" -> false
                        else -> return Response.json(mapOf("error" to "${layer.key} must be 1 or 0"), 400)
                    }
                }
                val date = q["date"]?.let { d ->
                    if (d == "today") null else runCatching { LocalDate.parse(d) }.getOrElse { return Response.json(mapOf("error" to "date must be YYYY-MM-DD or today"), 400) }
                }
                val scroll = q["scroll"]?.let { it.toIntOrNull()?.takeIf { px -> px >= 0 } ?: return Response.json(mapOf("error" to "scroll must be a whole number of px"), 400) }
                for ((layer, on) in set) if (AppSettings.horizonLayer(layer).value != on) {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    HorizonUi.setLayer(layer, on) { latch.countDown() }
                    latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                app.map.onMain {
                    if (q.containsKey("date")) HorizonUi.date = date
                    q["select"]?.let { name ->
                        HorizonUi.selected = if (name == "none") null else HorizonData.peaks?.candidates?.firstOrNull { it.peak.name.equals(name, ignoreCase = true) }?.let { HorizonUi.keyOf(it) }
                    }
                    scroll?.let { HorizonUi.scrollTo = it }
                }
                Thread.sleep(700) // the composition and the GNSS hold following the toggles
                answer(app, r)
            }
            "POST" to "/horizon/ui" -> {
                when (r.query["open"]) {
                    "1", "true" -> app.map.onMain { HorizonUi.open() }
                    "0", "false" -> app.map.onMain { HorizonUi.close() }
                    else -> return Response.json(mapOf("error" to "open=1 or open=0"), 400)
                }
                Thread.sleep(600)
                answer(app, r)
            }
            else -> null
        }
    }

    private fun answer(app: DystopoApp, r: Request): Response {
        val q = r.query
        val wait = q["wait"]?.let { it.toLongOrNull()?.coerceIn(0, 300_000) ?: return Response.json(mapOf("error" to "wait must be ms"), 400) } ?: 0L
        val date = q["date"]?.takeIf { r.method == "GET" && it != "today" }?.let { d ->
            runCatching { LocalDate.parse(d) }.getOrElse { return Response.json(mapOf("error" to "date must be YYYY-MM-DD or today"), 400) }
        } ?: app.map.onMain { HorizonUi.date }
        val h = if (wait > 0) Gnss.awaitHorizon(wait) else Gnss.horizonAtPhone()
        val layers = AppSettings.horizonLayers
        val peaks = h?.let { if (wait > 0) HorizonData.awaitPeaks(it, wait) else HorizonData.peaksFor(it) }
        val pos = Gnss.position()
        val now = System.currentTimeMillis()
        val sun = pos?.let { HorizonData.sunFor(h?.takeIf { s -> s.profile != null }, it.latitude, it.longitude, date, now) }
        val sunNow = pos?.let { io.github.jasondorjeshort.dystopo.nav.Sun.position(it.latitude, it.longitude, now) }
        val out = linkedMapOf<String, Any?>(
            "open" to HorizonUi.open, "layers" to layers.json(),
            "gnss" to linkedMapOf("held" to Gnss.held, "holders" to Gnss.holders(), "engine" to Gnss.engine),
            "position" to (pos != null), "drawn" to HorizonUi.drawn,
            "horizon" to Gnss.horizonJson(h)?.filterKeys { it != "angleDeg" },
            "horizonReason" to if (h == null) (if (pos == null) io.github.jasondorjeshort.dystopo.app.gnss.GnssText.NO_POSITION else io.github.jasondorjeshort.dystopo.app.gnss.GnssText.HORIZON_WORKING) else h.reason,
            "peaks" to HorizonData.peaksJson(peaks, HorizonUi.drawn?.let { HorizonUi.names.map { n -> HorizonUi.keyOf(n.peak) }.toSet() }), "peakRuns" to HorizonData.peakRuns.get(),
            "sun" to sun?.let { HorizonData.sunJson(it) + mapOf("nowAltitudeDeg" to sunNow?.apparentAltitudeDeg?.let { a -> Math.round(a * 10) / 10.0 }, "nowAzimuthDeg" to sunNow?.azimuthDeg?.let { a -> Math.round(a * 10) / 10.0 }) },
        )
        if (q["compare"] == "1" && sun != null) out["turnaround"] = compare(app, h, pos.latitude, pos.longitude, sun)
        return Response.json(out)
    }

    /** The turn-around clock's terrain day for the same point and day, beside the view's (`compare=1`). */
    private fun compare(app: DystopoApp, h: Gnss.HorizonState?, lat: Double, lon: Double, sun: HorizonData.SunDay): Map<String, Any?> {
        val pLat = h?.profile?.lat ?: lat
        val pLon = h?.profile?.lon ?: lon
        val at = sun.sun.terrain.flat.noonMs
        val until = SystemClock.elapsedRealtime() + 90_000
        var t = app.tiles.shade.terrainAt(pLat, pLon, at)
        while (t == null && SystemClock.elapsedRealtime() < until) {
            Thread.sleep(250)
            t = app.tiles.shade.terrainAt(pLat, pLon, at)
        }
        val d = t?.day
        return linkedMapOf(
            "answered" to (t != null), "noData" to t?.noData, "terrainSunriseMs" to d?.sunriseMs, "terrainSunsetMs" to d?.sunsetMs,
            "sunriseAgrees" to (d != null && d.sunriseMs == sun.sun.terrain.sunriseMs),
            "sunsetAgrees" to (d != null && d.sunsetMs == sun.sun.terrain.sunsetMs),
            "sunsetDifferenceMs" to if (d?.sunsetMs != null && sun.sun.terrain.sunsetMs != null) d.sunsetMs!! - sun.sun.terrain.sunsetMs!! else null,
        )
    }
}
