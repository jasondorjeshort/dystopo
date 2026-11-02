package io.github.jasondorjeshort.dystopo.app.controls

import android.graphics.PointF
import io.github.jasondorjeshort.dystopo.app.DystopoApp
import io.github.jasondorjeshort.dystopo.app.Request
import io.github.jasondorjeshort.dystopo.app.Response

/**
 * T101, the scale bar's debug routes (debug builds only, on the debug port):
 * - `GET /scalebar`: what the bar shows ([ScaleBarLayout]): `shown` or `hiddenBecause` (`noCamera`, `noScale`, T110 `notComposed` while the no-pack line shows; T106: no longer `tripCard`, it rides above the trip card and the update offer, `bottomCardsTopDp`;
 *   T115 `noRoomBelowTopLeft` when a tall bottom panel would push it up to the search box and the online indicator, `topLeftBottomDp`, and its own `topDp`),
 *   `bothUnits`, `units`, `night`, the camera's `zoom` and `lat` it was fitted at, `metersPerDp` (the shared Web
 *   Mercator rule), `density`, and per bar (the user's units first) `label`, `value`, `unit`, `meters`, `widthDp`,
 *   `widthPx` and `drawnPx` (tick to tick as drawn); where it sits (`boundsPx` in window pixels, its bottom edge,
 *   the credit line, route card and track panel edges it keeps above, and `clear`). `readBack` checks each bar
 *   against MapLibre itself on the live camera: `mapLibreMetersPerDp` (its projection at the centre latitude; per
 *   logical pixel, which is a dp), `meters` at that scale over `drawnPx` and `errorPct` against the label, and `measuredMeters`
 *   (the ground distance between two screen points `drawnPx` apart, T128: on the ruler's own row `measuredRowPx`) with its
 *   `measuredErrorPct`, and the same across the centre row (`centreRowMeters`, `centreRowErrorPct`). T128: under tilt
 *   (`tilt` at least 0.5 degrees) the bar is fitted to the scale on its own row (`measuredAt: row`, `rowPx`,
 *   `metersPerDp`), not the centre's Web Mercator rule (`centreMetersPerDp`), so the label is right where the bar is;
 *   `errorPct` (MapLibre's centre-latitude scale) then differs by the tilt's foreshortening and is meaningful untilted
 *   only. It also stays below the navigation card (`navCardBottomDp`, `clearOfNavCard`; hidden as
 *   `noRoomBelowNavCard` when no room is left).
 *   T110: only for a bar that is shown and fitted at the live camera (it refits every 500 ms; the route waits up to
 *   [FIT_WAIT_MS] for that); otherwise `readBack` is null and `readBackSkipped` says why (`hidden`, `fitBehindCamera`).
 *   Before, a hidden bar kept its last lengths and the read-back compared them with a camera that had moved.
 * - `POST /scalebar?both=on|off`: the Map options row "Scale bar" (one unit or both; T110: synced, `app.scaleBar.both`
 *   in `GET /sync/status` › `settingsSync`); answers like `GET` once the bar has redrawn. Anything else for `both` is a 400.
 *
 * T161, the live readout's strip on the bar's line ([io.github.jasondorjeshort.dystopo.app.readout.LiveReadout]):
 * - `GET /readout`: what the strip draws and why: `shown`, `hiddenBecause` (`notComposed`, `noRoomBelowNavCard`,
 *   `noRoomBelowTopLeft`, T177 `off` with the bar switch off; only every entry off leaves `drawn` empty), T177's `bar`
 *   (`on`, `window` with `s` and `label`, `windowFilledS`, `bothUnits`, and `fields` in drawn order: `entry`, `state`
 *   `value`, `zero` or `dash`, `text` as drawn, `other` in both-units mode, `caption`, `dimmed`), the `activity`
 *   (T172's remembered one) and `units`, `options` per entry (`shown=1;pace=0;damping=medium;pm=0;knots=0`, `barometer`,
 *   `knots` as honoured), `drawn` (per item as drawn: `entry`, `caption`, `state`, `value`, `unit`, `plusMinus`, `full`,
 *   `other`, `dimmed`, `trend`; T177: a gap is the first caption, "no fix 8 s", no item of its own), `line` (the strip as one line), `gap`, `text` per entry, `readout` (T160's
 *   [io.github.jasondorjeshort.dystopo.readout.Readout.json]: each value's `state`, SI `value`, `sigma`, `source`,
 *   `dimmed`, `frozen`, `reason`; `gapS`; `heights` with `gnssM`, `baroM` and `demM` apart; `barometer` with its `state`,
 *   `reason`, `offsetM`; `fixSpeedMps`, `routeSpeedMps`, `counters`, T177 `climbWindowS`, `climbFilledS`), `barometerSensor` (`present`, `listening`, `wanted`,
 *   `samples`, `lastHPa`, `source`: `sensor` or `replay`), `seaLevelPressure` (T173, `readout/PressureSource`: `state`
 *   off, fetching, in use, no station in range or unavailable; the prior's `hPa`, `station`, `distanceM`, `ageS`,
 *   `source`, `applied`; `limits`, `request`, `box`, `lastResponse`, `counts`), `route` (while
 *   navigating: `alongM`, `distanceM`, `onRoute`), `inputs` (counts per input, the clock offset) and `layout` (`placement`
 *   `beside` or `above`, T177 `naturalWidthPx` (the bar's one-row width it is chosen on) and `besideRoomPx`, `boundsPx`, `sizePx`, the scale bar's and the recentre button's bounds, `clearOfScaleBar`,
 *   `clearOfRecentre`, `clearOfNavCard`, `clearOfBottomPanels`, T192 `plateOpaque`). T192: in a gap of the fixes the
 *   climb holds the last fix's value (`dimmed`, `frozen`), then a dash with `reason` `no fix` past the climb window.
 *   T204: `layout` adds `drawnItems`, `droppedItems` (entries left out from the end of their order for room, font scale
 *   2.0) and `fits`; `readout.counters` add `newOffsets` (`newOffsetsSource`, `newOffsetsStep`) and `baroFallbacks`.
 * - `POST /readout/reset`: forgets every input (between replays); answers like `GET`.
 * - `POST /readout/stations[?url=<endpoint>]` (T173's test hook): the weather station fetch reads `url` instead of the
 *   registry's (`url=` empty restores it), forgets the stations and fetches at the next refresh; answers the URL.
 * - T178 `GET /readout` › `trend` ([io.github.jasondorjeshort.dystopo.app.readout.PressureWatch.json]): the barometer's
 *   trend and the pressure-fall warning: `setting` (`code`, `label`, `thresholdHPa`), `sampling` (`recording`,
 *   `barometerSwitch`, `present`, `trendWanted`), `synthetic`, `minutes` (one value a minute while a track records),
 *   `firstMinuteMs`, `lastMinute`, `latestMs`, `arrow` and `label` (as the bar draws them), `result` (`shared`'s
 *   `TrendResult.json`: `status` ok or notEnoughData, `shown` 3h or 1h, `equivalentHPa`, `band`, `alert`, `temperature`,
 *   `threeHour` and `oneHour` each with `mode` raw or reduced, `seaLevelChangeHPa`, `fallLowerHPa`, `interval` with its
 *   `random`, `sensor`, `height` and `temperature` terms, `datumM`, `netClimbM`, `sigmaTK`, `passes`, `reason`; or
 *   `threeHourMissing` / `oneHourMissing`), `alert` (`active`, `raisedAtMs`, `raisedBand`, `raises`, `clears`), `card`
 *   (`shown`, `title`, `body`, `station`, `boundsPx` (T192: null once the card is gone), `dismissedAtMs`), `spoken`, `station`, `stationTendency`,
 *   `persisted`, `counters`. `barometerSensor` adds `reasons` (`readout`, `trend`), `wakeUp`, `wakeUpAvailable`.
 * - T178 `POST /readout/trend?fixture=<name>` (`still-fall`, `climb-station`, `climb-standard`, `climb-1000-standard`,
 *   `last-hour-fall`, `treeline-step`, `forest-noise`, `recovery`; `shared`'s `PressureFixtures`) or a CSV body
 *   (`time,hPa,heightM,source,speedMps,lat,lon`, `PressureSeries`): replaces the series with the synthetic one moved to
 *   end now and evaluates it (the card, the spoken line and the arrow follow as live); live input is ignored meanwhile.
 *   `?clear=1` forgets it and the warning's memory (raised, its band, the 3 h no-repeat) and returns to live input. `?dismiss=1` presses the card's Dismiss. Answers `trend`.
 * T197, the known height (shared `readout/KnownHeights`, [io.github.jasondorjeshort.dystopo.app.readout.LiveReadout.pin]):
 * - `GET /readout` › `readout.barometer` adds `pinned`, `pinnedAt` (the inputs' clock), `pinnedName` (null for a typed
 *   height), `pinnedSigmaM`, `pinnedEleM`, `pinnable` (the barometer fused: a pin would hold) and `pinEnd` (why the last
 *   one ended: `sigma`, `barometer`, `disagreement`, `replaced`), and `readout.counters` `pins`, `pinsCleared`,
 *   `pinReleases`, `gnssHeldByPin`, `demHeldByPin`. `pin`: `pinned`, `pinnable`, `name`, `eleM`, `sigmaM`, `atMs`,
 *   `ageS`, `offsetSigmaM`, `holdsUnderM` (twice the pin's sigma: the mark clears when the offset's sigma reaches it),
 *   `end`, `mark` (`shown`, `glyph`, `label` "Set at <name>" or "Set by you", `detail` the tap's line, `boundsPx`,
 *   `taps`, `lastTapText`), `fix` (the last fix when fresh, 120 s) and `last` (the last pin: `via`, `name`, `eleM`,
 *   `sigmaM`, `confirmation`, `held`, `movedM`, `reason`). `line` ends with the mark's words while it is drawn.
 * - `POST /readout/calibrate?ele=<m>|height=<typed>[&name=<place>][&kind=peak|saddle|pass|sign][&sigma=<m>]` sets the
 *   height (`height` is read as the settings field reads it, in the user's units unless it names one; `kind` gives the
 *   sigma, 3 m for a peak, 5 m otherwise; a peak with a name, a sign without, by default); `?nearest=1` takes the
 *   candidate nearest the fresh fix as the long-press card finds them (409 with none, the candidates and rejections
 *   in the answer); `?menu=1` taps the open long-press card's "I am at" (409 when it does not offer one). 400 for a
 *   height out of range. Answers `result` (`held`, `movedM`, `reason`), `confirmation`, and `pin` as in `GET`.
 *   `POST /readout/reset` forgets the pin with every input.
 * T196, the way down after the warning ([io.github.jasondorjeshort.dystopo.app.readout.WayDownWatch], shared `nav/WayDown`):
 * - `GET /readout` › `wayDown` and `trend` › `wayDown` (so `POST /readout/trend` answers it too): `state` (`exposed`,
 *   `belowRidge`, `noWayDownWithinWindow`, `noData`, `noFix`, `noDem`, `cleared`, `notComputed`), `why` (`raise`,
 *   `minute`, `debug`), `computes`, `computedAtMs`, `computeMs`, `inputs` (the `fix`, `back` `recording` or `route`,
 *   `backPoints`, `navigating`, `alongM`, `aheadPoints`), `result` (shared `WayDownResult.json`: `exposed`, `eleM`,
 *   `tpiM`, `rule` with `ringM`, `thresholdM`, `dropM`, `sampleM`, `searchM`, `pace`, `exits` quicker first with
 *   `direction` `back` or `ahead`, `distanceM`, `timeS`, `minutes`, the point, `eleM`, `dropM`, `tpiM`; `searches` per
 *   direction `found`, `noneWithinWindow`, `noneBeforeLineEnd`, `noLine`; `card`, `spoken`), `card` (`lines` as drawn,
 *   `action`, `linesBoundsPx`, `actionBoundsPx`), `drawn`, `layerInstalled`, `stretchPoints`, `cameraFits`, `spoken`
 *   (the clause said with the warning; also in `trend.spoken` with `wayDown` true and in `GET /nav/speech` with index
 *   -6) and `help`.
 * - `POST /readout/trend?wayDown=1` computes the way down again now from the last fix (no new warning, nothing
 *   spoken); `?wayDownShow=1|0` presses the card's "Show the way down" or "Hide the way down" (409 when the card shows
 *   no way down). Both answer `trend`.
 * The options are settings (`POST /settings?readout.speed=pace=1`, `readoutBarometer=0`, T177 `readoutBar=0`,
 * `climbWindow=300`, T178 `pressureFallWarning=more`; `SettingsRoutes`).
 */
object ScaleBarRoutes {
    fun handle(app: DystopoApp, r: Request): Response? = when (r.method to r.path) {
        "GET" to "/scalebar" -> Response.json(state(app))
        "POST" to "/scalebar" -> set(app, r)
        // T161: the live readout's strip on the bar's line (readout/LiveReadout)
        "GET" to "/readout" -> Response.json(io.github.jasondorjeshort.dystopo.app.readout.LiveReadout.json())
        "POST" to "/readout/reset" -> Response.json(io.github.jasondorjeshort.dystopo.app.readout.LiveReadout.reset() + io.github.jasondorjeshort.dystopo.app.readout.LiveReadout.json())
        "POST" to "/readout/stations" -> Response.json(io.github.jasondorjeshort.dystopo.app.readout.PressureSource.configure(r.query["url"])) // T173
        "POST" to "/readout/trend" -> trend(app, r) // T178
        "POST" to "/readout/calibrate" -> calibrate(app, r) // T197
        else -> null
    }

    /** T197: `POST /readout/calibrate`. */
    private fun calibrate(app: DystopoApp, r: Request): Response {
        val live = io.github.jasondorjeshort.dystopo.app.readout.LiveReadout
        val kh = io.github.jasondorjeshort.dystopo.readout.KnownHeights
        fun answer(result: io.github.jasondorjeshort.dystopo.readout.PinResult, name: String?, eleM: Double, extra: Map<String, Any?> = emptyMap()): Response {
            val pin = live.json()["pin"]
            return Response.json(linkedMapOf("result" to result.json(), "confirmation" to kh.confirmation(name, eleM, io.github.jasondorjeshort.dystopo.app.AppSettings.units), "pin" to pin) + extra)
        }
        if (r.query["menu"] == "1") {
            val k = app.map.onMain { io.github.jasondorjeshort.dystopo.app.tracking.LongPressMenu.known }
                ?: return Response.json(mapOf("error" to "the long-press card offers no \"I am at\"", "longPress" to app.map.onMain { io.github.jasondorjeshort.dystopo.app.tracking.LongPressMenu.json() }), 409)
            app.map.onMain { io.github.jasondorjeshort.dystopo.app.tracking.LongPressMenu.setHeight() }
            // setHeight pins on its own thread: wait for it to land.
            val until = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < until) {
                val last = live.json()["pin"].let { (it as? Map<*, *>)?.get("last") as? Map<*, *> }
                if (last?.get("via") == "menu" && last["name"] == k.name) break
                Thread.sleep(50)
            }
            return Response.json(linkedMapOf("tapped" to io.github.jasondorjeshort.dystopo.readout.KnownHeights.menuLabel(k, io.github.jasondorjeshort.dystopo.app.AppSettings.units), "candidate" to k.json(), "pin" to live.json()["pin"]))
        }
        if (r.query["nearest"] == "1") {
            val fix = live.freshFix() ?: return Response.json(mapOf("error" to "no fresh fix"), 409)
            val found = kh.read({ z, x, y -> app.tiles.packs.osmCandidates(z, x, y).firstNotNullOfOrNull { it.tile(z, x, y) } }, fix.lat, fix.lon,
                io.github.jasondorjeshort.dystopo.routing.ElevationSampler { la, lo -> live.demAt(la, lo) })
            val k = found.candidates.firstOrNull() ?: return Response.json(mapOf("error" to "no named peak, saddle or pass with a height within ${kh.NEAR_FIX_M} m of the fix") + found.json(), 409)
            return answer(live.pin(k.name, k.eleM, k.sigmaM, "route"), k.name, k.eleM, mapOf("candidate" to k.json()) + found.json())
        }
        val units = io.github.jasondorjeshort.dystopo.app.AppSettings.units
        val ele = r.query["ele"]?.let { raw -> raw.trim().toDoubleOrNull()?.takeIf { it in kh.MIN_ELE_M..kh.MAX_ELE_M } ?: return Response.json(mapOf("error" to "ele must be metres between ${kh.MIN_ELE_M} and ${kh.MAX_ELE_M}"), 400) }
            ?: r.query["height"]?.let { raw -> kh.parseTyped(raw, units) ?: return Response.json(mapOf("error" to "height: $raw is not a height"), 400) }
            ?: return Response.json(mapOf("error" to "ele=<m>, height=<typed>, nearest=1 or menu=1 is required"), 400)
        val name = r.query["name"]?.trim()?.takeIf { it.isNotEmpty() }
        val kind = r.query["kind"]?.trim()?.lowercase() ?: if (name != null) "peak" else "sign"
        val sigma = r.query["sigma"]?.let { raw -> raw.toDoubleOrNull()?.takeIf { it > 0 && it <= 100 } ?: return Response.json(mapOf("error" to "sigma must be metres, above 0 and at most 100"), 400) }
            ?: kh.sigmaFor(kind)
        return answer(live.pin(name, ele, sigma, "route"), name, ele, mapOf("kind" to kind))
    }
    // end T197

    /** T178: `POST /readout/trend`. */
    private fun trend(app: DystopoApp, r: Request): Response {
        val w = io.github.jasondorjeshort.dystopo.app.readout.PressureWatch
        if (r.query["dismiss"] == "1") {
            app.map.onMain { w.dismiss() }
            return Response.json(w.json())
        }
        // T196: the way down, computed again now, and the card's "Show the way down" pressed.
        if (r.query["wayDown"] == "1") return Response.json(w.recomputeWayDown())
        r.query["wayDownShow"]?.let { raw ->
            val on = when (raw.trim().lowercase()) {
                "1", "on", "true" -> true
                "0", "off", "false" -> false
                else -> return Response.json(mapOf("error" to "wayDownShow must be 1 or 0 (got $raw)"), 400)
            }
            val wd = io.github.jasondorjeshort.dystopo.app.readout.WayDownWatch
            if (on && wd.ui?.lines.isNullOrEmpty()) return Response.json(mapOf("error" to "the card shows no way down") + mapOf("wayDown" to wd.json()), 409)
            app.map.onMain { wd.setDrawn(on) }
            Thread.sleep(800) // the camera's fit (600 ms) and a frame
            return Response.json(w.json())
        }
        // end T196
        return try {
            val body = r.bodyText.takeIf { it.isNotBlank() }
            Response.json(w.feedSynthetic(r.query["fixture"], body, r.query["clear"] == "1"))
        } catch (e: Exception) {
            val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
            Response.json(mapOf("error" to (cause.message ?: cause.javaClass.simpleName)), 400)
        }
    }

    private fun set(app: DystopoApp, r: Request): Response {
        val raw = r.query["both"] ?: return Response.json(mapOf("error" to "both=on|off is required"), 400)
        val on = when (raw.trim().lowercase()) {
            "on", "1", "true", "yes" -> true
            "off", "0", "false", "no" -> false
            else -> return Response.json(mapOf("error" to "both must be on or off (got $raw)"), 400)
        }
        app.map.onMain { ScaleBarSettings.setBothUnits(on) }
        // Wait for the bar to recompose with one or two lengths (it polls the camera every 500 ms at most).
        val want = if (on) 2 else 1
        val until = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < until && ScaleBarLayout.shown && (ScaleBarLayout.bars.size != want || ScaleBarLayout.drawnPx.size != want)) Thread.sleep(50)
        Thread.sleep(100) // one more frame, so drawnPx belongs to the new bars
        return Response.json(state(app))
    }

    private fun state(app: DystopoApp): Map<String, Any?> {
        val why = awaitFit(app)
        return ScaleBarLayout.json() + mapOf("readBack" to if (why == null) readBack(app) else null, "readBackSkipped" to why)
    }

    /** T110: null once the bar is shown and fitted at the live camera, else why not after [FIT_WAIT_MS]. */
    private fun awaitFit(app: DystopoApp): String? {
        val until = System.currentTimeMillis() + FIT_WAIT_MS
        while (true) {
            val live = runCatching { app.map.onMain { app.map.map?.cameraPosition?.let { c -> c.target?.let { doubleArrayOf(c.zoom, it.latitude, c.tilt) } } } }.getOrNull()
            if (live != null && ScaleBarLayout.fittedAt(live[0], live[1], live[2])) return null
            if (System.currentTimeMillis() >= until) return if (!ScaleBarLayout.shown) "hidden" else "fitBehindCamera"
            Thread.sleep(50)
        }
    }

    /** T110: how long `GET /scalebar` waits for the bar to catch up with the camera (it refits every 500 ms). */
    private const val FIT_WAIT_MS = 1500L

    private fun readBack(app: DystopoApp): List<Map<String, Any?>>? = runCatching {
        app.map.onMain {
            val m = app.map.map ?: return@onMain null
            val view = app.map.mapView ?: return@onMain null
            val lat = m.cameraPosition.target?.latitude ?: return@onMain null
            val mpp = m.projection.getMetersPerPixelAtLatitude(lat) // per logical pixel (dp), not per device pixel
            val density = view.resources.displayMetrics.density
            val drawn = ScaleBarLayout.drawnPx
            val cx = view.width / 2f
            val cy = view.height / 2f
            val row = ScaleBarLayout.rowPx.takeIf { it.isFinite() } ?: cy // T128: the ruler's own row
            ScaleBarLayout.bars.mapIndexed { i, b ->
                val px = drawn.getOrNull(i) ?: (b.px * ScaleBarLayout.density).toFloat()
                fun across(y: Float) = m.projection.fromScreenLocation(PointF(cx - px / 2, y)).distanceTo(m.projection.fromScreenLocation(PointF(cx + px / 2, y)))
                val measured = across(row)
                val atCentre = across(cy)
                linkedMapOf(
                    "label" to b.label,
                    "drawnPx" to round(px.toDouble(), 2),
                    "mapLibreMetersPerDp" to round(mpp, 5),
                    "meters" to round(px / density * mpp, 2),
                    "errorPct" to round((px / density * mpp - b.meters) / b.meters * 100, 3),
                    // T128: measured on the bar's own row (`rowPx`; under tilt the scale differs from the centre's)
                    "measuredRowPx" to Math.round(row),
                    "measuredMeters" to round(measured, 2),
                    "measuredErrorPct" to round((measured - b.meters) / b.meters * 100, 3),
                    "centreRowMeters" to round(atCentre, 2), // the same length across the centre row, for comparison
                    "centreRowErrorPct" to round((atCentre - b.meters) / b.meters * 100, 3),
                )
            }
        }
    }.getOrNull()

    private fun round(v: Double, digits: Int): Double {
        var s = 1.0
        repeat(digits) { s *= 10 }
        return Math.round(v * s) / s
    }
}
