package io.github.jasondorjeshort.dystopo.app.aircraft

import io.github.jasondorjeshort.dystopo.app.AppSettings
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response
import io.github.jasondorjeshort.dystopo.app.SettingsRoutes

/**
 * T311: the Flights layer's debug routes (debug builds, behind the loopback token). Its three settings are `POST
 * /settings` keys (`aircraft`, `aircraftRefreshS`, `aircraftAirports`; `SettingsRoutes.kt`).
 * - `GET /aircraft`: `on`, `state` (`waiting`, `live`, `offline`, `stopped`, `limited`) and its `message` (the status line
 *   shown, null when none), `pausedBecause` (`off`, `stopped (<state>)` in the background, `horizonViewOpen`, null while
 *   it polls), `source` (`adsb.lol` or the replay's name) and `credit`, `refreshS` (T316: with the interval's `staleS`,
 *   `dropS` and `moveCapS`, and `hoursLeft`, how long the day's requests left last at it), `airportNames`, `query` (the last
 *   request's rounded centre, radius and path), `lastUrl`, `lastResponseMs`, `lastCount`, `requests` (live requests this
 *   run), `skippedLimited`, `quota` (`utcDay`, `spentToday`, `leftToday`, `daily`, `limited`, `limitedForS`) and
 *   `quotaLine` (the settings row's line), `observer` (`heightM`, `undulationM`, `undulationOverride`; no position),
 *   `routes` (the table's counts and `loadMs`), `count`, `stale`, `labelled`, `selected`, `drawing` (the credit line
 *   names the feed), `installedOnStyle`, `pushes`, `ticks`, `card` (the card's `lines` and `heightPx` while one is
 *   selected), and `aircraft`, closest first: `hex`, `callSign`, `registration`, `type`, `lat`, `lon` (moved up),
 *   `reportedLat`, `reportedLon`, `movedS`, `altitudeM` (EGM2008), `altitudeKind` (`geometric`, `barometric`, `ground`,
 *   `none`), `altGeomFt`, `altBaroFt`, `onGround`, `trackDeg`, `groundSpeedKt`, `verticalRateFpm`, `ageS`, `stale`,
 *   `distanceKm`, `bearingDeg`, `elevationDeg` (from the phone), `route` (`airports`, `plausible`, `leg`, `origin`,
 *   `destination`, `crossTrackKm`, `toleranceKm`, `reason`), `label`, `trailPoints`; T316: `groundM` (the DEM pack's
 *   ground where it last reported, EGM2008; null without a tile there), `aboveGroundM` and `aboveGroundApproximate` (the
 *   card's "above the ground" line: null with no ground, on or below it; approximate from pressure altitude), with the
 *   poller's `groundSamples` and `lastGroundMs` (the last poll's sampling time). Feed data is answered from memory;
 *   nothing of it is stored. T314, the route lines: `routeLines` (the setting, `selected` or `all`; `POST /settings`
 *   key `aircraftRouteLines`), `estimatedPastPoints` (the selected flight's dotted estimate, 0 when none), `estimatedFrom`
 *   (its origin's code) and `estimatedPastWhyNot` (why none: `route not plausible`, `track starts at the origin`,
 *   `track starts on an earlier leg`, `track passed the origin after it began`, ...), `remainingPoints`, `routeLinesDrawn`
 *   (flights with route lines: the selected one and each faint line), `routeLinesCapped` (plausible flights left without
 *   a faint line by `routeLinesCap`, 50), `faintLines`, `faintPointsMax`, and the faint source's `faintUpdatedAtMs`
 *   (built), `faintPushedAtMs` (set on the map), `faintUpdates` and `faintPushes` (they move on a poll, a selection or a
 *   setting change, not each second); `offMainCalls` (MapLibre calls that arrived off the main thread, reposted: 0 is
 *   the rule), `lastOffMain` (the last one's caller, with a stack in a debug build) and `skippedDetached` (pushes skipped
 *   for a destroyed activity's view).
 *   T364, who asks and what an idle layer costs: `mapShown` ("Show flights", `mapFlights`), `mapAsksBecause` (why the map
 *   does not ask: `off`, `hidden`, the lifecycle's `stopped (<state>)`, `horizonViewOpen` for any sky view over it,
 *   `noView`; null while it asks), `pausedBecause` adds `nobodyAsks`; `tickerRunning` (the once-a-second ticker: only while
 *   Flight tracking is on), `polls` (polls made, the feed's or a replay's; `requests` counts the feed's alone), `rebuilds`
 *   and `publishes` (views built and handed to the map and the sky views: none while nobody asks), `pushes` (views set on
 *   the map), `pushesSkipped` (views the map did not push: covered, in the background or hidden) and `clears` (the map's
 *   marks cleared on hiding or turning off).
 * - `POST /aircraft`: `on=1|0` (Flight tracking, as the row sets it: stored, synced), T364: `map=1|0` ("Show flights", as
 *   its row in Map options sets it: stored, synced, acted on at once), `select=<hex>|nearest|none` (as a
 *   tap on the map does), `offline=1|0` (polls fail as with no connection, without a request), `spent=<n>` (the day's
 *   count), `limit=<s>` (a 429 with Retry-After `s`; 0 lifts it). Answers `GET /aircraft` after `wait=<ms>` (default 800).
 * - `POST /aircraft/replay`: the body is a replay file (`{"loopS", "frames": [{"t", "ac": [readsb aircraft]}]}`; the
 *   fixture `shared/src/commonTest/resources/aircraft/rainier-replay.json`), played from now instead of the live feed;
 *   `name=` labels it; `undulationM=` stands for the fix's undulation (an emulator fix has none); `on=0` leaves Flight
 *   tracking as it is (default: turned on). `stop=1` (no body) goes back to the live feed. Answers `GET /aircraft` after
 *   `wait=<ms>` (default 2500, two ticks and a poll).
 */
object AircraftRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/aircraft" && !r.path.startsWith("/aircraft/")) return null
        return when (r.method to r.path) {
            "GET" to "/aircraft" -> Response.json(answer(app))
            "POST" to "/aircraft" -> {
                val q = r.query
                q["on"]?.let { v -> setOn(app, flag(v) ?: return bad("on=1 or on=0")) }
                q["map"]?.let { v -> SettingsRoutes(app).apply(SettingsRoutes.Parsed.Ok.of(AppSettings.MAP_FLIGHTS, flag(v) ?: return bad("map=1 or map=0"))) } // T364
                q["offline"]?.let { v -> AircraftPoller.forceOffline = flag(v) ?: return bad("offline=1 or offline=0") }
                q["spent"]?.let { v -> AircraftPoller.setSpent(v.toIntOrNull()?.takeIf { it >= 0 } ?: return bad("spent must be a whole number")) }
                q["limit"]?.let { v -> AircraftPoller.limit(v.toLongOrNull()?.takeIf { it >= 0 } ?: return bad("limit must be seconds")) }
                q["select"]?.let { v ->
                    val hex = when (v) {
                        "none" -> null
                        "nearest" -> AircraftLayer.drawn.minByOrNull { it.distanceM ?: 0.0 }?.hex ?: return bad("no aircraft drawn")
                        else -> v.lowercase().takeIf { h -> AircraftLayer.drawn.any { it.hex == h } } ?: return bad("no aircraft $v drawn")
                    }
                    app.map.onMain { AircraftLayer.select(hex) }
                }
                Thread.sleep(wait(q, 800) ?: return bad("wait must be ms"))
                Response.json(answer(app))
            }
            "POST" to "/aircraft/replay" -> {
                val q = r.query
                q["undulationM"]?.let { v -> AircraftPoller.undulationOverride = if (v == "none") null else v.toDoubleOrNull() ?: return bad("undulationM must be metres or none") }
                if (q["stop"] == "1") {
                    AircraftPoller.setReplay(null)
                } else {
                    if (r.body.isEmpty()) return bad("the body is the replay file (or stop=1)")
                    try {
                        AircraftPoller.setReplay(r.bodyText, q["name"] ?: "replay")
                    } catch (e: IllegalArgumentException) {
                        return bad("not a replay file: ${e.message}")
                    }
                    if (q["on"] != "0" && !AppSettings.AIRCRAFT.value) setOn(app, true)
                }
                Thread.sleep(wait(q, 2500) ?: return bad("wait must be ms"))
                Response.json(answer(app))
            }
            else -> null
        }
    }

    private fun answer(app: DystopoApp): Map<String, Any?> {
        val out = LinkedHashMap(AircraftLayer.json())
        val sel = app.map.onMain { AircraftLayer.selected }
        out["card"] = sel?.let { h ->
            AircraftLayer.drawn.firstOrNull { it.hex == h }?.let { a ->
                linkedMapOf(
                    "title" to a.title,
                    "lines" to io.github.jasondorjeshort.dystopo.aircraft.AircraftText.card(a, AppSettings.units, AppSettings.AIRCRAFT_AIRPORTS.value, AircraftPoller.credit, AircraftLayer.estimatedFrom),
                    "heightPx" to AircraftCardLayout.heightPx,
                )
            }
        }
        return out
    }

    /** Flight tracking on or off as the settings row sets it (stored, synced, acted on). */
    private fun setOn(app: DystopoApp, on: Boolean) {
        SettingsRoutes(app).apply(SettingsRoutes.Parsed.Ok.of(AppSettings.AIRCRAFT, on))
    }

    private fun flag(v: String): Boolean? = when (v) {
        "1", "true", "on" -> true
        "0", "false", "off" -> false
        else -> null
    }

    private fun wait(q: Map<String, String>, default: Long): Long? = q["wait"]?.let { it.toLongOrNull()?.coerceIn(0, 60_000) } ?: if (q.containsKey("wait")) null else default

    private fun bad(msg: String) = Response.json(mapOf("error" to msg), 400)
}
