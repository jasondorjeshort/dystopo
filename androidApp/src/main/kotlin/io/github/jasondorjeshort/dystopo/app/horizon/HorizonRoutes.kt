package io.github.jasondorjeshort.dystopo.app.horizon

import android.os.SystemClock
import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.app.SettingsRoutes
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
 *   (the rim's names), `namesDrawn`, `peaksListed`, `hollowDrawn` (T309: the names whose peak is just out of sight), `leadersDrawn` (the names with a leader line),
 *   `sunPathDrawn` (samples), `sunHourMarks`, `sunNowDrawn`, `selected`, `scrollPx`, `scrollMaxPx`, `switchPx` and
 *   `switchSelected` (T309: the view's "Map | Horizon" switch)), `horizon` (as `GET /gnss`'s, without the per-degree
 *   angles) or null with `horizonReason`, `peaks` (T309: `reason`, `ms`, `tilesRead`, `tested`, `visible`, `skyline`,
 *   `candidates` (in sight or out of sight by no more than `hiddenM`), `outOfSight`, `hiddenM`, `named` (null before the
 *   plot drew), and each candidate, closest first: `name`, `eleM`, `distanceM`, `bearingDeg`, `compass`, `angleDeg`,
 *   `clearanceDeg`, `inSight`, `skyline`, `dominant`, `promM`, `score` and `rank` (`HorizonLabels`), `named`), `sun` (`date`, `onTerrain`, the terrain
 *   and flat `…SunriseMs`/`…SunsetMs` with their clock times and UTC, `noonMs`, `sunMinutes`, `alwaysUp`, `sunlit`,
 *   `gaps`, `pathSamples`, `pathClear`, `highestDeg`, `hourMarks`; `nowAltitudeDeg`, `nowAzimuthDeg`). No position.
 *   T312: `layers` has `flights` (the stored toggle, `horizonFlights`) and `flightTracking` (T311's setting: the layer and
 *   its button only while on); `skyRange`; `flights` (null while the layer does not draw): `state`, `message`, `range`,
 *   `credit`, `owners` (the poller's askers: `horizon` while the view asks), `held` (the poller's aircraft), `listed`,
 *   `onGround` and `noAltitude` (left out), `hollow`, `named` (null before the plot drew), and each flight listed, closest
 *   first: `hex`, `label`, `kind`, `emergency`, `bearingDeg`, `elevationDeg`, `distanceM`, `altitudeM`, `altitudeKind`,
 *   `horizonDeg` (the terrain's angle at its bearing), `hidden`, `stale`, `hollow`, `ageS`, `tailPoints`, `tailS`, `named`;
 *   `drawn` adds `flightTracking`, `buttons`, `skyRange`, `flightsDrawn`, `flightNames`, `flightsHollow`,
 *   `flightsEmergency`, `flightTailPoints`, `flightsListed`, `selectedFlight`, `flightCard`, `creditShown`.
 *   `date=YYYY-MM-DD` answers for that day (the screen keeps its own); `wait=<ms>` waits that long for the horizon and the
 *   peaks; `compare=1` adds `turnaround`: the turn-around clock's terrain day for the same point and day
 *   (`ShadeService.terrainAt`, waited up to 90 s) with `sunsetAgrees` and `sunriseAgrees`.
 * - `POST /horizon?layers=satellites,peaks,sun,flights|all|none` or `satellites=1|0`, `peaks=`, `sun=`, `flights=`: the
 *   toggles, as a tap sets them (stored, synced); `date=YYYY-MM-DD|today`: the day the Sun layer shows; `select=<peak name>|none`:
 *   picks a candidate peak as a tap does; T312: `flight=<hex or call sign>|none` picks a listed flight as a tap on its dot
 *   or row does (its card opens), `skyRange=overhead|near|all` sets Sky range as its row does; `scroll=<px>`: scrolls the
 *   control area. Answers `GET /horizon` (with `wait`). T321: `viewFrom=<peak name>` is a picked peak's "View from here"
 *   on a candidate of the view as it stands (404 when none is named so): the view stands on the peak, named.
 *   T321: `horizonSweeps` (the sweeps since launch) and `horizonsKept` (`Gnss.HORIZON_KEEP` at most: the fix's beside the points').
 *   T317: `point` (`lat`, `lon`, `name`: the chosen point, null at the fix), `placeLine` (`text`, `moved`, `kind`
 *   `here|named|offset|unknown`, `distanceM`, `bearingDeg` from the fix); `horizon`, `peaks` and `sun` are the point's, and
 *   `drawn` adds `point`, `placeLine`, `placeKind`, `moved`, `backShown`, `disabledButtons`.
 * - `POST /horizon/ui?open=1|0`: opens or closes the Horizon view, as the settings row and the switch's "Map" do (open at the fix);
 *   answers `GET /horizon`. T317: `lat=&lon=` opens it for that point as the long-press card's "Sky from here" does (on a
 *   named feature within `radius=` metres, default 100, at the feature and named); `back=1` returns to the fix as
 *   "Back to here" does. With `wait=<ms>` the answer waits for the point's horizon and peaks.
 * - `POST /horizon/switch?to=map|horizon` (T309): selects a segment of the "Map | Horizon" switch, as a tap on it does (on the
 *   map or on the view); answers `{to, open}` and the switch's place: `map` (the map's switch as `GET /layout` reads it:
 *   `rectPx`, `state`, `reason`) and `view` (the view's own, `drawn.switchPx`). T321: "Horizon" opens the view at the map's
 *   selection as a tap does (`ViewSwitch.openAtSelection`: an open long-press card, else a search result's marker, else the
 *   fix); the answer adds `from` (`longPress`, `search` or `fix`) and `point` (null at the fix). T326: `to=panorama` too, the
 *   switch's third segment ("Map | Horizon | Panorama"): from the map it opens the Panorama at the map's selection by the
 *   same call, from the Horizon view it changes the view and keeps the place and a picked peak; the answer adds `showing`
 *   (`map`, `horizon` or `panorama`) and `viewPx` (the showing sky view's own switch; `view` stays the Horizon view's).
 *   `open` stays true for either sky view (`HorizonUi.open`, the one open state); the Panorama's own routes are
 *   `app/panorama/PanoramaRoutes.kt`'s.
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
                    val l = HorizonLayers.parse(spec) ?: return Response.json(mapOf("error" to "layers must be satellites, peaks, sun and flights, comma-separated, or all or none"), 400)
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
                val range = q["skyRange"]?.let { io.github.jasondorjeshort.dystopo.horizon.SkyRange.of(it) ?: return Response.json(mapOf("error" to "skyRange must be overhead, near or all"), 400) }
                range?.let { SettingsRoutes(app).apply(SettingsRoutes.Parsed.Ok.of(AppSettings.SKY_RANGE, it)); app.map.onMain { io.github.jasondorjeshort.dystopo.app.SettingsUi.revision++ } }
                // T321: "View from here" on a candidate peak of the view as it stands (a tap on the picked peak's button).
                val viewFrom = q["viewFrom"]?.let { name ->
                    HorizonData.peaks?.candidates?.firstOrNull { it.peak.name.equals(name, ignoreCase = true) }
                        ?: return Response.json(mapOf("error" to "no candidate peak named $name in the view as it stands"), 404)
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
                    viewFrom?.let { HorizonUi.viewFrom(it) }
                    q["flight"]?.let { want ->
                        HorizonUi.selectedFlight = if (want == "none") null else {
                            val f = HorizonData.flightsFor(Gnss.horizonAtPhone(), Gnss.position(), AppSettings.skyRange).list
                            f.firstOrNull { it.hex.equals(want, true) || it.aircraft.title.equals(want, true) }?.hex
                        }
                    }
                }
                Thread.sleep(700) // the composition and the GNSS hold following the toggles
                answer(app, r)
            }
            "POST" to "/horizon/switch" -> {
                val to = io.github.jasondorjeshort.dystopo.horizon.SkyView.of(r.query["to"])
                    ?: return Response.json(mapOf("error" to "to=map, to=horizon or to=panorama"), 400)
                val horizon = to != io.github.jasondorjeshort.dystopo.horizon.SkyView.MAP
                app.map.onMain { io.github.jasondorjeshort.dystopo.app.controls.ViewSwitch.select(to) }
                // T321: from a long-press card the view opens once its named feature is looked up (a thread of its own).
                val until = SystemClock.elapsedRealtime() + 10_000
                while (horizon && !app.map.onMain { HorizonUi.open } && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
                Thread.sleep(800)
                Response.json(switchJson(app, r.query["to"]!!))
            }
            "POST" to "/horizon/ui" -> {
                val q = r.query
                val lat = q["lat"]?.let { it.toDoubleOrNull()?.takeIf { v -> v in -85.0..85.0 } ?: return Response.json(mapOf("error" to "lat must be degrees"), 400) }
                val lon = q["lon"]?.let { it.toDoubleOrNull()?.takeIf { v -> v in -180.0..180.0 } ?: return Response.json(mapOf("error" to "lon must be degrees"), 400) }
                val radius = q["radius"]?.let { it.toDoubleOrNull()?.takeIf { v -> v >= 0 } ?: return Response.json(mapOf("error" to "radius must be metres"), 400) } ?: HorizonData.FEATURE_PRESS_M
                when {
                    (lat == null) != (lon == null) -> return Response.json(mapOf("error" to "lat and lon go together"), 400)
                    lat != null && lon != null -> {
                        val p = HorizonData.pointFor(lat, lon, radius) // T317: as "Sky from here" looks it up
                        app.map.onMain { HorizonUi.openAt(p) }
                    }
                    q["back"] == "1" -> app.map.onMain { HorizonUi.back() }
                    q["open"] == "1" || q["open"] == "true" -> app.map.onMain { HorizonUi.open() }
                    q["open"] == "0" || q["open"] == "false" -> app.map.onMain { HorizonUi.close() }
                    else -> return Response.json(mapOf("error" to "open=1, open=0, lat=&lon= or back=1"), 400)
                }
                Thread.sleep(600)
                answer(app, r)
            }
            else -> null
        }
    }

    /** T309: the switch's two places after a selection: the map's (by its layout id) and the view's own. */
    private fun switchJson(app: DystopoApp, to: String): Map<String, Any?> = app.map.onMain {
        val id = io.github.jasondorjeshort.dystopo.ui.OverlayIds.HORIZON_SWITCH
        val map = io.github.jasondorjeshort.dystopo.app.MapLayout.observed().firstOrNull { it.id == id }
        linkedMapOf(
            "to" to to, "open" to HorizonUi.open,
            // T321: what the last "Horizon" stood on (`longPress`, `search` or `fix`) and the view's point (null at the fix).
            "from" to io.github.jasondorjeshort.dystopo.app.controls.ViewSwitch.lastFrom?.key,
            "point" to HorizonUi.point?.let { p -> linkedMapOf("lat" to p.lat, "lon" to p.lon, "name" to p.name) },
            "map" to map?.let { s -> linkedMapOf("rectPx" to s.rectPx?.map { Math.round(it) }, "state" to s.state, "reason" to s.reason) },
            "view" to if (HorizonUi.open) HorizonUi.switchPx else null,
            // T326: the view showing and the sky view's own switch (the Horizon view's or the Panorama's).
            "showing" to io.github.jasondorjeshort.dystopo.app.controls.ViewSwitch.showing.key,
            "viewPx" to if (!HorizonUi.open) null else if (HorizonUi.view == io.github.jasondorjeshort.dystopo.horizon.SkyView.PANORAMA) io.github.jasondorjeshort.dystopo.app.panorama.PanoramaUi.switchPx else HorizonUi.switchPx,
        )
    }

    private fun answer(app: DystopoApp, r: Request): Response {
        val q = r.query
        val wait = q["wait"]?.let { it.toLongOrNull()?.coerceIn(0, 300_000) ?: return Response.json(mapOf("error" to "wait must be ms"), 400) } ?: 0L
        val date = q["date"]?.takeIf { r.method == "GET" && it != "today" }?.let { d ->
            runCatching { LocalDate.parse(d) }.getOrElse { return Response.json(mapOf("error" to "date must be YYYY-MM-DD or today"), 400) }
        } ?: app.map.onMain { HorizonUi.date }
        // T317: the view's point (the chosen one, else the fix) and the place line, as the screen works them out.
        val point = app.map.onMain { HorizonUi.point }
        val fix = Gnss.position()
        val src = io.github.jasondorjeshort.dystopo.horizon.HorizonPlace.source(point, fix?.latitude, fix?.longitude)
        val place = io.github.jasondorjeshort.dystopo.horizon.HorizonPlace.line(point, fix?.latitude, fix?.longitude, AppSettings.units)
        val h = when {
            point == null -> if (wait > 0) Gnss.awaitHorizon(wait) else Gnss.horizonAtPhone()
            else -> awaitAt(point.lat, point.lon, wait)
        }
        val moved = point != null
        val layers = AppSettings.horizonLayers
        val tracking = AppSettings.AIRCRAFT.value
        val peaks = h?.let { if (wait > 0) HorizonData.awaitPeaks(it, wait) else HorizonData.peaksFor(it) }
        val pos = fix
        val now = System.currentTimeMillis()
        val sun = src?.let { HorizonData.sunFor(h?.takeIf { s -> s.profile != null }, it.first, it.second, date, now) }
        val sunNow = src?.let { io.github.jasondorjeshort.dystopo.nav.Sun.position(it.first, it.second, now) }
        val out = linkedMapOf<String, Any?>(
            "open" to HorizonUi.open, "layers" to layers.json(), "flightTracking" to tracking, "skyRange" to AppSettings.skyRange.key,
            "gnss" to linkedMapOf("held" to Gnss.held, "holders" to Gnss.holders(), "engine" to Gnss.engine),
            "position" to (pos != null), "drawn" to HorizonUi.drawn,
            "point" to point?.let { p -> linkedMapOf("lat" to p.lat, "lon" to p.lon, "name" to p.name) },
            "placeLine" to linkedMapOf(
                "text" to place.text, "moved" to place.moved, "kind" to place.kind,
                "distanceM" to place.distanceM?.let { Math.round(it) }, "bearingDeg" to place.bearingDeg?.let { Math.round(it * 10) / 10.0 },
            ),
            "horizon" to Gnss.horizonJson(h)?.filterKeys { it != "angleDeg" },
            "horizonReason" to if (h == null) (if (src == null) io.github.jasondorjeshort.dystopo.app.gnss.GnssText.NO_POSITION else io.github.jasondorjeshort.dystopo.app.gnss.GnssText.HORIZON_WORKING) else h.reason,
            "peaks" to HorizonData.peaksJson(peaks, HorizonUi.drawn?.let { HorizonUi.names.map { n -> HorizonUi.keyOf(n.peak) }.toSet() }), "peakRuns" to HorizonData.peakRuns.get(),
            "horizonSweeps" to Gnss.horizonSweeps.get(), "horizonsKept" to Gnss.horizonsKept, // T321: back to the fix sweeps nothing
            "flights" to if (io.github.jasondorjeshort.dystopo.horizon.HorizonPlace.layers(layers.effective(tracking), moved).flights) HorizonData.flightsJson(HorizonData.flightsFor(h, pos, AppSettings.skyRange, now), HorizonUi.drawn?.let { HorizonUi.flightMarks.filter { m -> m.box != null }.map { m -> m.hex } }) else null,
            "sun" to sun?.let { HorizonData.sunJson(it) + mapOf("nowAltitudeDeg" to sunNow?.apparentAltitudeDeg?.let { a -> Math.round(a * 10) / 10.0 }, "nowAzimuthDeg" to sunNow?.azimuthDeg?.let { a -> Math.round(a * 10) / 10.0 }) },
        )
        if (q["compare"] == "1" && sun != null && src != null) out["turnaround"] = compare(app, h, src.first, src.second, sun)
        return Response.json(out)
    }

    /** T317: the horizon at a chosen point, waiting up to [ms] for its sweep (none: as it stands now). */
    private fun awaitAt(lat: Double, lon: Double, ms: Long): Gnss.HorizonState? {
        val until = SystemClock.elapsedRealtime() + ms
        while (true) {
            Gnss.horizonAt(lat, lon)?.let { return it }
            if (SystemClock.elapsedRealtime() > until) return null
            Thread.sleep(100)
        }
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
