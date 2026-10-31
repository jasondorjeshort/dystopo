package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.MapController.CameraMode
import io.github.jasondorjeshort.dystopo.app.nav.NavSession
import io.github.jasondorjeshort.dystopo.app.nav.NavSources
import io.github.jasondorjeshort.dystopo.nav.NavOptions
import io.github.jasondorjeshort.dystopo.routing.Directions
import io.github.jasondorjeshort.dystopo.routing.DirectionsText
import io.github.jasondorjeshort.dystopo.routing.TravelMode

/**
 * Debug routes for navigation mode (T61; registered with one line in [Routes], before [TrackRoutes] so it
 * sees `POST /location` first):
 * - `POST /nav/start?route=current|<kept route id or id prefix>|<saved route id, id prefix or file>` (T45's plan on
 *   the map; T65: a route kept in the trip store, as `GET /routes` lists them; else a route in `files/routes/`), `?track=<id>` (a stored track), `?file=<name>` (a GPX in `files/tracks|routes|exports`),
 *   or a GPX body (its route, or its track with `prefer=track`). Options: `offRouteM` (50), `offRouteS` (10),
 *   `backOnRouteM` (30), `arriveM` (30), `mode=hike|bike|drive|crow` (the plan's mode for `route=current`),
 *   `camera=follow|heading|free` (follow), `zoom` (15; `zoom=keep`), `sound=0`, `vibrate=0`. Answers like `GET /nav`.
 * - `GET /nav`: the path (length, climb, planned time, waypoints), the latest state (progress, distance off
 *   the line, next waypoint and end with distance, climb, time, ETA; the rejoin hint), heading and its
 *   source, compass, camera mode, keep-screen-on, alerts, fix counts per origin.
 * - `GET /nav/status`: `offRoute` now, `counts` per event type and every event (`offRoute` with `afterS`
 *   from the first fix off the line, `backOnRoute` with `maxDistanceM`, `waypoint:<name>`, `arrived`).
 * - T65: `POST /nav/snooze?min=10` (alerts silent for 10 minutes, at most 240), `?until=back` (silent until back on the
 *   route, so the next excursion sounds again), `?off=1`; events are still detected and listed; `snooze` in `/nav` and
 *   `/nav/status` (`suppressed` counts silenced alerts). The off-route banner has the same two buttons.
 * - T65 `GET /nav` also has `etaVsPlan`, a pace ratio: (fix time since the first fix + the model's time still to go to
 *   the end, the way back included when off route) / the planned time (the route's [io.github.jasondorjeshort.dystopo.model.RouteSummary]
 *   time for routes, the line's model time for tracks and files); 1.0 at the start, above 1 behind plan, below 1 ahead;
 *   `pace` has its three parts. `fix`: stale-fix handling (a GPS fix or the last known location more than 30 s old when it
 *   arrives is dropped, `droppedTooOld`; the last known location more than 2 km from the line does not start navigation,
 *   `droppedTooFar`; with no usable fix for 30 s the bar says "No GPS fix for N s", `lost`; T91: the 30 s and 2 km are the
 *   settings `navStaleS` and `navStartM`, read at each start, and the banner's Snooze length is `navSnoozeMin`). `layout`: where the Tracks and
 *   Routes chips sit relative to the stats bar ([TopChips]; T146: `follow`, the edges they followed in the card's own layout
 *   pass, and `drawCheck`, frames drawn with a chip under the card since the start). `path.totalAscentM` is the route's one ascent figure (T24 rule).
 * - Starting navigation closes the route editor sheet and hides the T45 route card; the navigation line draws above
 *   the planned and kept route lines (T65).
 * - `POST /nav/stop`; `POST /nav/camera?mode=follow|heading|free`; `POST /nav/heading?deg=` (simulated
 *   heading for injected fixes; no `deg` clears it).
 * - `POST /location?...&bearing=<deg>` (observed, not handled): the bearing becomes the simulated heading.
 * - T102, turn directions: navigation carries the route's directions (the plan's for `route=current`, read from the
 *   packs on the device for a kept or saved route). `GET /nav/status` adds `instructions` (those shown at the default
 *   detail level: `index`, `type`, `priority`, `text`, `alongM`, `leadM`), `nextInstruction` (`index`, `type`, `text`,
 *   `distanceM` and `distance` ahead in the units preference, `withinLead`; null once none is left) and `passed`;
 *   `GET /nav` has `directions` (counts) and the card's `ui.turn`, `ui.turnDistance`, `ui.turnSoon`.
 *   `GET /nav/directions[?route=current|<kept or saved route>][&level=1|2|3]` lists a route's directions without
 *   navigating (`count`, `shown`, `byPriority`, each with `text` and `inDistance`, "In 300 m, turn left onto ...");
 *   T108: a kept trip's are the directions saved with it (`source.directions` `saved`; no graph needed), else read
 *   from the packs (`packs`), and `/nav` `source` says the same; a crow route has one, the arrival;
 *   `POST /nav/directions?open=1|0` opens or closes the list on the route card ("Show N directions").
 * - T126, spoken directions ([io.github.jasondorjeshort.dystopo.app.nav.NavSpeech]): `GET /nav/speech` lists what was said since
 *   navigation last started (`spoken`: `atMs`, `index` into `/nav/status` `instructions`, `text` as spoken, `direction` the
 *   card's words, `voice` as the engine got it with the units spelt out, `engine`), with `enabled`, `count`,
 *   `skippedWhileOff` and the engine's state (`ready`, `said`, `focus` requests, grants and releases, `lastError`).
 *   `POST /nav/speech?enabled=1|0` flips the `navSpeech` setting (as Settings does, synced); `engine=silent|platform`
 *   (debug builds: `silent` records without audio, for the emulator); `clear=1` empties the list. Answers like the GET.
 * - T137: `POST /nav/speech?mute=on|off` mutes this navigation (the card's speaker button; the `navSpeech` setting is not
 *   changed, the next navigation starts unmuted): a direction due while muted is listed with `muted: true` and the
 *   engine gets nothing (`engine.said` stays). `GET /nav/speech` and `GET /nav` `speech` carry `muted`, `mutedCount`,
 *   `state` (`off`, `notStarted`, `starting`, `ready`, `noEngine`, `noVoice`) and `notice` (null, or `text`, `kind`,
 *   `detail`, `shownAtMs`, `dismissedAtMs`: the card's line when the engine cannot speak, once per navigation);
 *   `/nav` adds `muteButtonShown`. `dismiss=1` dismisses the notice as its Dismiss does. Debug builds:
 *   `engine=failing` (an engine whose init fails as with no text-to-speech engine installed) and `engine=novoice` (no
 *   voice for English) bring up the notice on the emulator.
 * - T163, the turn-around clock ([io.github.jasondorjeshort.dystopo.nav.TurnAround]): `GET /nav` `turnAround` while
 *   navigating: `text` and `label` as the card draws them ("Turn back by 14:10", "Sunset 19:12, flat horizon, 30 min
 *   margin, standard pace"), `clock` (`state` `ahead|margin|turn|returning|arrival|noDeadline`, `colour`
 *   `normal|amber|red`, `deadline`, `marginMin`, `pace`, `loop`, `alongM`, `retraceS`, `onwardS`, `returnS`, `way`
 *   `back|on`, `turnBackMs`, `warnAtMs`, `arrivalMs`, `arrivalIfTurnedAtMs`), the same times as clock strings, `alerts`
 *   (`marginGiven`, `turnGiven`, `events` with each alert's `kind`, `atMs`, `text`, `colour` and the `spoken` line) and
 *   `spoken`, `farthestM` (more than 100 m short of it the walker has turned back: `returning`); `alerts.turnAround` counts the alert path's tones. The clock reads the wall clock (a replay's old fix times
 *   do not move it) and the settings `turnDeadline` and `turnMarginMin` (`POST /settings`) at every reading.
 *   `GET /nav/turnaround`: the same while navigating; else, in free tracking, the clock along the track being recorded
 *   (the way out back along it to its start; `source` `recording`), or `{"active": false}`. T180: navigating, also
 *   `headingBack` and `wayBackStretches` (where the line runs back over itself, from the flip on); `GET /nav/status` has
 *   `headingBack` and `tracker` (the retrace fix's `jumps` to the way back after a sustained backward stretch,
 *   `fullSearches`, `lastJump`) and its events a `headingBack` per flip. In free tracking the reading is
 *   `WayBackClock`'s: the way back's `anchor` (`source` `start`, `walk` or `here`), `retrace` (`returning`, `headingBack`,
 *   `flips`), the clock, the card's `ui`, `shownOnCard`, `layout.cardPx`, and the `alerts` and `spoken` line of this
 *   recording (`POST /tracks/wayback` moves the anchor; `TrackRoutes.kt`).
 * Fixes reach navigation from `POST /location`, `POST /location/replay` (the GPX's own times, so off-route
 * timing is the same at any replay rate; T146: `?route=current&kmh=<n>` replays the current route timed in the app, no
 * body) and the device GPS while navigating.
 */
object NavRoutes {
    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.method == "POST" && r.path == "/location") {
            r.query["bearing"]?.toDoubleOrNull()?.let { NavSession.simulateHeading(it, apply = false) }
            return null
        }
        if (r.path != "/nav" && !r.path.startsWith("/nav/")) return null
        return when (r.method to r.path) {
            "GET" to "/nav" -> Response.json(NavSession.json())
            "GET" to "/nav/status" -> Response.json(NavSession.status())
            "GET" to "/nav/turnaround" -> Response.json( // T163
                NavSession.turnJson()?.let { mapOf("active" to true) + it }
                    ?: NavSession.freeTrackingTurnJson()?.let { mapOf("active" to true) + it + mapOf("layout" to mapOf("cardPx" to io.github.jasondorjeshort.dystopo.app.nav.WayBackLayout.boundsPx?.toList())) }
                    ?: mapOf("active" to false, "reason" to "not navigating and not recording"),
            )
            "POST" to "/nav/stop" -> Response.json(NavSession.stop() + NavSession.json())
            "POST" to "/nav/start" -> start(r)
            "GET" to "/nav/directions" -> directions(r) // T102
            "GET" to "/nav/speech" -> Response.json(io.github.jasondorjeshort.dystopo.app.nav.NavSpeech.json()) // T126
            "POST" to "/nav/speech" -> speech(app, r)
            "POST" to "/nav/directions" -> {
                val open = r.query["open"] ?: return bad("open=1 or open=0 required")
                app.map.onMain { io.github.jasondorjeshort.dystopo.app.routing.RouteSession.directionsOpen = open == "1" }
                Response.json(io.github.jasondorjeshort.dystopo.app.routing.RouteSession.json())
            }
            "POST" to "/nav/camera" -> {
                val m = camera(r.query["mode"]) ?: return bad("mode must be follow, heading or free")
                NavSession.setCamera(m)
                Response.json(NavSession.json())
            }
            "POST" to "/nav/snooze" -> { // T65
                val off = r.query["off"] == "1"
                val until = r.query["until"]
                val min = r.query["min"]?.let { it.toDoubleOrNull() ?: return bad("min must be a number") }
                if (!off && until != "back" && min == null) return bad("min=<minutes>, until=back or off=1 required")
                try {
                    Response.json(mapOf("snooze" to NavSession.snooze(if (until == "back") null else min, off)) + NavSession.json())
                } catch (e: IllegalArgumentException) {
                    bad(e.message ?: "bad snooze")
                }
            }
            "POST" to "/nav/heading" -> {
                val raw = r.query["deg"]
                val d = raw?.takeIf { it.isNotEmpty() }?.let { it.toDoubleOrNull() ?: return bad("deg must be a number") }
                NavSession.simulateHeading(d) // re-runs the last fix with it before answering
                Response.json(NavSession.json())
            }
            else -> Response.notFound()
        }
    }

    private fun start(r: Request): Response {
        val q = r.query
        fun d(k: String): Double? = q[k]?.let { it.toDoubleOrNull() ?: throw IllegalArgumentException("$k must be a number") }
        return try {
            val preferTrack = q["prefer"] == "track"
            val res = when {
                q["route"] != null -> NavSources.route(q["route"]!!)
                q["track"] != null -> NavSources.track(q["track"]!!)
                q["file"] != null -> NavSources.file(q["file"]!!, preferTrack)
                r.body.isNotEmpty() -> NavSources.gpx(r.bodyText, preferTrack, q["name"])
                else -> return bad("route=, track=, file= or a GPX body required")
            }
            val mode = q["mode"]?.let { TravelMode.of(it) ?: return bad("mode must be ${TravelMode.labels}") } ?: res.mode ?: TravelMode.HIKE
            val defaults = NavOptions()
            val opts = NavOptions(
                offRouteM = d("offRouteM") ?: defaults.offRouteM,
                offRouteSeconds = d("offRouteS") ?: defaults.offRouteSeconds,
                backOnRouteM = d("backOnRouteM") ?: minOf(defaults.backOnRouteM, d("offRouteM") ?: defaults.offRouteM),
                arriveM = d("arriveM") ?: defaults.arriveM,
                mode = mode,
            )
            val cam = camera(q["camera"] ?: "follow") ?: return bad("camera must be follow, heading or free")
            val zoom = if (q["zoom"] == "keep") null else d("zoom") ?: 15.0
            Response.json(NavSession.start(res.path, res.source, opts, cam, q["sound"] != "0", q["vibrate"] != "0", zoom, res.planTimeS, res.directions))
        } catch (e: NavSources.NotFound) {
            Response.json(mapOf("error" to e.message), 404)
        } catch (e: IllegalArgumentException) {
            bad(e.message ?: "bad request")
        }
    }

    /** T126: `POST /nav/speech?enabled=1|0&engine=silent|platform&clear=1`; T137: `mute=on|off`, `dismiss=1`, `engine=failing|novoice`. */
    private fun speech(app: DystopoApp, r: Request): Response {
        val speech = io.github.jasondorjeshort.dystopo.app.nav.NavSpeech
        val q = r.query
        if (listOf("enabled", "engine", "clear", "mute", "dismiss").none { q[it] != null }) {
            return bad("enabled=1|0, mute=on|off, engine=${speech.ENGINES.joinToString("|")}, dismiss=1 or clear=1 required")
        }
        val mute = q["mute"]?.let { m ->
            when (m.lowercase()) { "on", "1", "true" -> true; "off", "0", "false" -> false; else -> return bad("mute must be on or off") }
        }
        q["engine"]?.let { e -> try { speech.setEngine(e) } catch (x: IllegalArgumentException) { return bad(x.message ?: "bad engine") } }
        q["enabled"]?.let { raw ->
            when (val p = SettingsRoutes.parse(mapOf("navSpeech" to raw))) {
                is SettingsRoutes.Parsed.Error -> return bad(p.message)
                is SettingsRoutes.Parsed.Ok -> { SettingsRoutes(app).apply(p); app.map.onMain { SettingsUi.revision++ } }
            }
        }
        if (q["clear"] == "1") speech.clear()
        mute?.let { speech.setMuted(it) } // T137
        if (q["dismiss"] == "1") speech.dismissNotice()
        app.map.onMain { } // the card's state posted by those has been applied
        return Response.json(speech.json())
    }

    /** T102: `GET /nav/directions`: a route's turn directions at a detail level, without navigating. */
    private fun directions(r: Request): Response = try {
        val res = NavSources.route(r.query["route"] ?: "current")
        val level = r.query["level"]?.let { it.toIntOrNull()?.takeIf { l -> l in 1..3 } ?: return bad("level must be 1, 2 or 3") } ?: Directions.DEFAULT_LEVEL
        val mode = res.mode ?: TravelMode.HIKE
        val units = AppSettings.units
        val shown = Directions.shown(res.directions, level)
        Response.json(
            linkedMapOf(
                "source" to res.source, "mode" to mode.label, "level" to level, "count" to res.directions.size, "shown" to shown.size,
                "byPriority" to res.directions.groupingBy { it.priority }.eachCount().toSortedMap().mapKeys { "p${it.key}" },
                "instructions" to shown.mapIndexed { i, ins ->
                    ins.json(mode) + linkedMapOf("index" to i, "inDistance" to (if (i == 0) null else DirectionsText.withDistance(ins, ins.alongM - shown[i - 1].alongM, units, mode)))
                },
            ),
        )
    } catch (e: NavSources.NotFound) {
        Response.json(mapOf("error" to e.message), 404)
    }

    private fun camera(s: String?): CameraMode? = when (s?.lowercase()) {
        "follow" -> CameraMode.FOLLOW
        "heading", "heading-up", "headingup" -> CameraMode.HEADING_UP
        "free", "off" -> CameraMode.FREE
        else -> null
    }

    private fun bad(message: String) = Response.json(mapOf("error" to message), 400)
}
