package io.github.jasondorjeshort.dystopo.app

import io.github.jasondorjeshort.dystopo.app.tracking.LongPressMenu
import io.github.jasondorjeshort.dystopo.app.tracking.ReplayChecks
import io.github.jasondorjeshort.dystopo.app.tracking.ReplayScript
import io.github.jasondorjeshort.dystopo.app.tracking.Tracking
import io.github.jasondorjeshort.dystopo.gpx.TrackStats
import io.github.jasondorjeshort.dystopo.model.Waypoint
import io.github.jasondorjeshort.dystopo.pmtiles.MiniJson
import io.github.jasondorjeshort.dystopo.store.LocationFix
import io.github.jasondorjeshort.dystopo.store.StoredWaypoint
import io.github.jasondorjeshort.dystopo.store.TrackExport
import io.github.jasondorjeshort.dystopo.store.TrackImport
import io.github.jasondorjeshort.dystopo.store.TrackSummary

/**
 * Debug routes for tracks (T31, T40), consulted by [Routes] before its own (debug builds only):
 * - `GET /tracks`: recorder state (mode, paused, accepted/rejected/paused fixes, gap splits, live
 *   stats), every stored track with its (store-cached) stats, the shown track, open sheet and
 *   profile, overlay, settings and store info (incl. `statsComputed`, the stats cache misses).
 *   T159: `state` (`idle`, `recording`, `waiting-for-gps`, `paused`, `auto-paused`, `stopped-by-system`), each track's
 *   `gaps` count, and in `recording`: `state` and `stateLine` (what the controls show), `pendingGap`, `silentS` (since the
 *   last GPS fix) and `lastAccuracyM`, `watchdog` (`count` of re-registrations, `timeoutMs` from the profile,
 *   `registrations` of the GPS listener), `fallback` (`provider`, `listening`, `fixes`, `ignored`), `notification`
 *   (`title`, `text`, `posts`, `dismissals`, `reposts`), the service (`serviceForeground`, `serviceDown`,
 *   `serviceRestarts` by the system, `tasksRemoved`), `flagged`, `gapsWritten` and `gaps` (the active track's gap list:
 *   `start`, `end`, `durationS`, `reason`). `lastFix` carries the accuracy, speed, course and vertical accuracy.
 *   T251: in `recording`, `counts` (`accepted`, `flagged`, `rejectedSpeed`, `rejectedTime`, `gapSplits`, `dropped`,
 *   `lastFlaggedAccuracyM` and the waiting line's words, `line`), `head` (the recorder's last kept point: position,
 *   accuracy, `flags`) and `puck` (`GET /location`'s `puck`, with `toHeadM`, its distance from the head).
 *   T260: the gaps the map bridges with a dashed line ([io.github.jasondorjeshort.dystopo.gpx.TrackDrawing.gaps]): in
 *   `recording`, `bridged` (`count`, `totalS`, `text` as the panel's statistics line says it, `intervalMs` the threshold,
 *   and `list`: each gap's `from` and `to` as `[lat, lon]`, `startTime`, `endTime`, `durationS`, `straightM`); `overlay`
 *   gains `bridges` and `bridgedS` (what the map draws now); `GET /tracks/{id}` gains the same `bridged`; `ui.gapText` and
 *   `ui.profileGapText` are the two statistics lines' gap words.
 * - `POST /tracks/record?name=&source=all|gps|injected[&activity=foot|bike|road|rail|boat|air]`: start recording (stops the
 *   current track); T172: with that activity's profile from the settings rows (default: the remembered activity, which
 *   this does not change). Tracks and the recording carry `activity`; the recording also `profile` (its four choices and the
 *   encoded `value`). The activity picker's and the battery card's routes: [io.github.jasondorjeshort.dystopo.app.tracking.ActivityPicker.handle],
 *   [io.github.jasondorjeshort.dystopo.app.tracking.BatteryExemption.handle].
 * - `POST /tracks/stop`, `POST /tracks/pause`, `POST /tracks/resume`.
 * - T180, the way back of the recording ([io.github.jasondorjeshort.dystopo.app.nav.WayBackClock]): `GET /tracks` `wayBack`
 *   (null when nothing records) is `GET /nav/turnaround`'s free-tracking reading: `anchor` (`source` `start` | `walk` |
 *   `here`, `index` of the recording's point, `lat`, `lon`, `timeMs`, the card's `line`, `override`), `retrace`
 *   (`returning`, `headingBack`, `flips`, `flipsAt`, `alongM`, `totalM`, `offM`), the `clock`, `text`, `label`, `ui`.
 *   `POST /tracks/wayback?here=1` is the recording card's "The way back starts here" (the recording's last point; kept per
 *   device, never synced); `POST /tracks/wayback?reset=1` goes back to the rule. 409 when nothing records.
 *   T252: the card is off until turned on for the recording: `POST /tracks/wayback?on=1|0` is the recording panel's
 *   "Turn-back time" chip (kept per track beside the override), `basis=1|0` (with it or alone) opens or closes the card's
 *   basis, as a tap on the card does. `wayBack` gains `on`, `activity`,
 *   `activitySource`, `paceMode` (`hike`, `bike`, null), `noClock` (`off`, `activity`, `tooFar`, or null while the clock
 *   shows), `retraceS`, `maxWayBackS`, `deadlineDate` (the deadline's own date, the recording's day), `basisOpen`, and
 *   `ui.clock`, `ui.basis`; the reading (`text`, `label`, `clock`) is null while no clock shows.
 * - T262, the Tracks and routes screen ([io.github.jasondorjeshort.dystopo.app.tracks.TracksScreen], which replaced T219's Tracks
 *   and Routes sheets): `POST /tracks/ui?open=1|0[&filter=all|tracks|routes]` opens it (on Tracks unless `filter` says, and
 *   unfolds the bar) or closes it; `filter=` alone changes the filter; `more=1|0` the title row's overflow (Sync now, Sync
 *   status, Share), `import=1|0` the Import line; `menu=<id, or kind:id, or none>` opens a row's menu as its dots do, and with
 *   it `action=` taps an entry (`statistics`, `open`, `rename`, `export`, `go`, `activity`, `report`, `shareLink`, `delete`):
 *   `rename` with `name=` renames as Save does, `delete` with `confirm=1` deletes as the question's Delete does, without either
 *   they open the row's name field or question; `eye=<id>` taps a row's eye (shown to hidden or back). T253: `&bar=1|0` unfolds
 *   the bar or hides it as Hide does; `&importHoldMs=` holds the next import after its start line, for a screenshot.
 *   `GET /tracks/ui` answers `open`, `title`, `filter`, `filters` (`code`, `label` with its count, `selected`), `moreOpen`,
 *   `overflow`, `importOpen`, `message`, `menu` (the row's key) and `menuEntries`, `renaming`, `deleteAsk` and
 *   `deleteQuestion`, `counts` (`tracks`, `routes`, `shown`, `hidden`), `rows` under the filter, newest first (`kind`, `id`,
 *   `name`, `line`, `activity`, `shown`, `state` "Shown" or "Hidden", `recording`, `counts`), `saveFile`
 *   ([io.github.jasondorjeshort.dystopo.app.SaveFile.json]) and `bar` (`open`, `recordGroup`, `discardAsk`).
 * - `GET /tracks/settings`; `POST /tracks/settings?gapS=600`: the automatic new-segment gap in
 *   seconds (0 = never split on time), persisted.
 * - `GET /tracks/{id}` (summary, stats, waypoints, T159 `gaps`), `GET /tracks/{id}.gpx` (the GPX export, ids in
 *   `dystopo:id` extensions), `POST /tracks/{id}/export` (write it to `files/tracks/`),
 *   `POST /tracks/{id}/show[?on=1|0]` (T262: shows or hides it on this device's map, kept, never synced), `DELETE /tracks/{id}`.
 * - T262: `GET /tracks` `shown` is the shown set (`tracks` and `routes`, ids), `hidden` the hidden one, `shownId` the one
 *   track a reader of one track draws ([Tracking.shownId]); each track has `shown`. `overlay` gains `tracks` and `routes`
 *   (the ids drawn), `live` (the recording drawn on top) and `storedSends` (how often the stored tracks went to the map).
 * - `GET /tracks/{id}/profile?n=200`: elevation profile samples (T24's sampling);
 *   `POST /tracks/{id}/profile` opens the profile panel on the map; `POST /tracks/close` closes the
 *   profile panel and the waypoint sheet.
 * - `POST /tracks/{id}/waypoint?name=[&lat=&lon=&ele=]`: a waypoint at the given or the last known position
 *   (`/tracks/-/waypoint` for a free-standing one).
 * - `GET /tracks/waypoints/{wid}`; `POST /tracks/waypoints/{wid}?name=` renames;
 *   `DELETE /tracks/waypoints/{wid}`; `POST /tracks/waypoints/{wid}/select` opens the rename/delete
 *   sheet (what tapping the waypoint on the map does).
 * - T91, waypoints at any time: `POST /waypoint[?lat=&lon=][&name=]` is the map's Waypoint chip (shown
 *   folded or not, recording or not): a waypoint at the given point or at "here" (the recorder's last fix or
 *   the map's last device fix, whichever is newer), on the recording track if one records, else
 *   free-standing; it opens the waypoint sheet. 409 with no position. `GET /tracks` lists the
 *   free-standing ones (`freeWaypoints`, drawn whatever track is shown) and `lastDrop`.
 *   `POST /map/longpress?lat=&lon=` is a long-press on the map: it opens the long-press card (T114: "Route to
 *   here", "Waypoint here", "Route from here", Cancel), or while a route waits for its end (or, after Choose on map,
 *   its start) passes straight to the route; `POST /map/longpress/to|waypoint|route|cancel` taps the card's entries
 *   (`to` is Route to here, `route` Route from here); `GET /map/longpress` its state (`lastAction`, `actions`).
 * - `POST /tracks/import` with a GPX body (`?name=` names a track the file leaves unnamed), or
 *   `POST /tracks/import?file=<name>` for a file in `files/tracks/`; `GET /tracks/files` lists those.
 *   Tracks keep their ids from `dystopo:id`; ids already stored are skipped.
 * - `POST /location/replay?rate=10[&record=name][&accuracy=5]` with a GPX body: replays its track
 *   points as injected fixes at `rate` × real time (optionally recording them into a new track; T57: 409
 *   with `record=` while a track is already recording,
 *   source `injected`); `GET /location/replay` progress; `DELETE /location/replay` cancels.
 *   T146: `POST /location/replay?route=current&kmh=40[&rate=30]` with no body replays the current route as a timed
 *   track (one fix a second at `kmh`, the same fixes as `GET /route/gpx?track=1&kmh=`) built in the app, so the 1 MiB
 *   body limit does not bind (404 with no route; a body as well is a 400). `GET /location/replay` has `running`,
 *   `finished` (the last replay sent every fix), `cancelled`, `source` (`body` or `route`), `kmh`, `fixes`, `sent`,
 *   `rate`, `durationMs` and `elapsedMs`.
 *   T159 ([ReplayScript]): `accuracy=5,120@40-60,200@80` reports fixes 40 to 60 (numbered from 0) at 120 m and fix 80 at
 *   200 m over the default 5 m (a point's own accuracy otherwise); `stall=60@100` holds the replay for 60 s of real time
 *   before fix 100 (`stall=60`: before the middle fix), the later fixes stamped 60 s later, so the GPS goes silent;
 *   `GET /location/replay` gains `script` and `stalling`. A bad `accuracy=` or `stall=` is a 400 before anything starts.
 *
 * - T203, the terrain estimate ([io.github.jasondorjeshort.dystopo.app.tracking.TerrainSource]): `GET /location/terrain` is its
 *   state (`state` `idle|waiting|estimating|gave-up|no-fix`, `noFixReason`, `watching` and `off`, `seed` with its time,
 *   position, barometer reading, course, pace and why the heading term is on or off, `particles`, `spreadM`, `last`, the
 *   counts, the `graph` it loaded, and `estimates`: the last 400 with their spread, heading use and, during a replay, the
 *   distance to the replay's own fix, `replayErrorM`). `POST /location/terrain[?compass=course|sensor]` with a GPX body
 *   (a `pressure` column, [io.github.jasondorjeshort.dystopo.app.tracking.Replay]) resets the source and starts the replay
 *   with `POST /location/replay`'s parameters (`rate`, `record`, `accuracy` ranges, `stall`); `compass=course` (the default)
 *   takes the heading from the replay's own bearing, `sensor` from the phone's compass; `giveUpM=30..300` lowers the give-up
 *   limit for that run only, to see the give-up and the grey dot on a replay. No body: the reset alone. 409 while
 *   the source does not watch (the setting off, no barometer, not on foot, or in the background without a recording).
 *
 * `POST /location` (T10/T27) is observed, not handled: the fix also goes to the recorder's injected
 * source and [handle] returns null so [Routes] still moves the puck. T159: `&provider=network` (or `passive`) gives the
 * fix that source, so it stands in for the recording's network fallback (taken only while the GPS is silent).
 */
object TrackRoutes {
    private const val UUID = "[0-9a-fA-F-]{36}"
    private val trackPath = Regex("^/tracks/($UUID|-)(\\.gpx|/waypoint|/export|/show|/profile)?$")
    private val waypointPath = Regex("^/tracks/waypoints/($UUID)(/select)?$")

    fun handle(app: DystopoApp, r: Request): Response? {
        if (r.path != "/tracks" && !r.path.startsWith("/tracks/") && !r.path.startsWith("/location") && r.path != "/waypoint" && !r.path.startsWith("/map/longpress")) return null
        io.github.jasondorjeshort.dystopo.app.tracking.ActivityPicker.handle(app, r)?.let { return it } // T172: the picker, a track's activity
        io.github.jasondorjeshort.dystopo.app.tracking.BatteryExemption.handle(app, r)?.let { return it } // T172: the battery card
        // T229: before the tracker is ready, GET /tracks answers at once with `ready: false` (Tracking.readiness) instead of waiting.
        if (r.method == "GET" && r.path == "/tracks" && Tracking.readyOrNull == null) return Response.json(Tracking.readiness())
        val t = Tracking.of(app)
        val p = params(r)
        when (r.method to r.path) {
            "POST" to "/location" -> {
                val lat = p.d("lat")
                val lon = p.d("lon")
                if (lat != null && lon != null) {
                    // T159: provider=network (or passive) stands in for the recording's network fallback.
                    val source = when (p.s("provider")?.lowercase()) { "network" -> "network"; "passive" -> "passive"; else -> "debug" }
                    t.injected.publish(LocationFix(lat, lon, p.d("ele") ?: p.d("alt"), System.currentTimeMillis(), p.d("accuracy") ?: 10.0, source))
                }
                return null
            }
            "GET" to "/tracks" -> return Response.json(Tracking.readiness() + overview(t)) // T229: ready first
            "GET" to "/tracks/ui" -> return Response.json(screenJson(t))
            "POST" to "/tracks/ui" -> return screenPost(app, t, p) // T262 (T219's sheet before)
            "POST" to "/waypoint" -> {
                val lat = p.d("lat")
                val lon = p.d("lon")
                if ((lat == null) != (lon == null)) return Response.json(mapOf("error" to "pass both lat and lon, or neither"), 400)
                val w = t.dropWaypoint(lat, lon, p.s("name"), via = "route")
                    ?: return Response.json(mapOf("error" to "no position yet: pass lat and lon, or inject a fix first"), 409)
                return Response.json(storedJson(w) + mapOf("sheetOpen" to (t.selectedWaypointId == w.waypoint.id), "drop" to t.lastDrop))
            }
            "GET" to "/map/longpress" -> return Response.json(app.map.onMain { LongPressMenu.json() })
            "POST" to "/map/longpress" -> {
                val lat = p.d("lat") ?: return Response.json(mapOf("error" to "lat and lon required"), 400)
                val lon = p.d("lon") ?: return Response.json(mapOf("error" to "lat and lon required"), 400)
                return Response.json(app.map.onMain { LongPressMenu.longPress(lat, lon); LongPressMenu.json() })
            }
            "POST" to "/map/longpress/to", "POST" to "/map/longpress/waypoint", "POST" to "/map/longpress/route", "POST" to "/map/longpress/cancel" -> {
                val action = r.path.substringAfterLast('/')
                val ok = app.map.onMain {
                    val open = LongPressMenu.point != null
                    when (action) {
                        "to" -> LongPressMenu.routeTo() // T114
                        "waypoint" -> LongPressMenu.waypoint()
                        "route" -> LongPressMenu.route()
                        else -> LongPressMenu.cancel()
                    }
                    open
                }
                if (!ok) return Response.json(mapOf("error" to "the long-press card is not open"), 409)
                return Response.json(app.map.onMain { LongPressMenu.json() })
            }
            "POST" to "/tracks/record" -> {
                val mode = when (p.s("source")?.lowercase()) {
                    null, "all" -> Tracking.Mode.ALL
                    "gps" -> Tracking.Mode.GPS
                    "injected" -> Tracking.Mode.INJECTED
                    else -> return Response.json(mapOf("error" to "source must be all, gps or injected"), 400)
                }
                val activity = p.s("activity")?.let { io.github.jasondorjeshort.dystopo.model.Activity.of(it.lowercase()) ?: return Response.json(mapOf("error" to "activity must be foot, bike, road, rail, boat or air"), 400) } // T172
                val s = t.start(p.s("name"), mode, activity)
                return Response.json(mapOf("started" to summaryJson(s), "service" to t.serviceState))
            }
            "POST" to "/tracks/stop" -> {
                val s = t.stop() ?: return Response.json(mapOf("error" to "not recording"), 409)
                return Response.json(trackJson(t, s))
            }
            "POST" to "/tracks/pause" -> {
                if (!t.pause()) return Response.json(mapOf("error" to "not recording"), 409)
                return Response.json(recorderJson(t))
            }
            "POST" to "/tracks/resume" -> {
                if (!t.resume()) return Response.json(mapOf("error" to "not recording"), 409)
                return Response.json(recorderJson(t))
            }
            "POST" to "/tracks/wayback" -> { // T180
                val nav = io.github.jasondorjeshort.dystopo.app.nav.WayBackClock
                val on = p.s("on") // T252: the turn-back card on or off for this recording, as the chip
                val basis = p.s("basis") // T252: the card's basis open or closed, as a tap on the card
                if ((on != null && on != "0" && on != "1") || (basis != null && basis != "0" && basis != "1")) return Response.json(mapOf("error" to "on and basis take 0 or 1"), 400)
                val ok = when {
                    p.s("here") == "1" -> t.wayBackHere()
                    p.s("reset") == "1" -> t.wayBackReset()
                    on != null || basis != null -> (on == null || nav.setOn(on == "1")).also { ok ->
                        if (ok && basis != null) java.util.concurrent.CountDownLatch(1).let { l -> android.os.Handler(android.os.Looper.getMainLooper()).post { nav.basisOpen = basis == "1"; l.countDown() }; l.await(2, java.util.concurrent.TimeUnit.SECONDS) }
                        if (ok) Thread.sleep(300) // a tick publishes the card
                    }
                    else -> return Response.json(mapOf("error" to "here=1, reset=1, on=0|1 or basis=0|1"), 400)
                }
                if (!ok) return Response.json(mapOf("error" to "not recording, or no point recorded yet"), 409)
                return Response.json(mapOf("wayBack" to io.github.jasondorjeshort.dystopo.app.nav.WayBackClock.json()))
            }
            "GET" to "/tracks/settings" -> return Response.json(settingsJson(t))
            "POST" to "/tracks/settings" -> {
                p.s("gapS")?.let { raw ->
                    val v = raw.toLongOrNull()
                    if (v == null || v < 0 || v > 86_400) return Response.json(mapOf("error" to "gapS must be 0 (never split) to 86400 seconds"), 400)
                    t.setGapSeconds(v)
                }
                return Response.json(settingsJson(t))
            }
            "POST" to "/tracks/close" -> {
                t.selectWaypoint(null)
                t.showProfile(null)
                return Response.json(mapOf("closed" to true))
            }
            "GET" to "/tracks/files" -> return Response.json(
                mapOf("dir" to t.filesDir.path, "files" to t.importableFiles().map { mapOf("name" to it.name, "bytes" to it.length(), "modified" to it.lastModified()) }),
            )
            "POST" to "/tracks/import" -> return import(t, r, p)
            "GET" to "/location/replay" -> return Response.json(t.replay.json())
            "DELETE" to "/location/replay" -> {
                t.replay.cancel()
                return Response.json(t.replay.json())
            }
            "POST" to "/location/replay" -> return replay(t, r, p)
            // T203: the terrain source's estimates; a replay with a pressure column run through it.
            "GET" to "/location/terrain" -> return Response.json(io.github.jasondorjeshort.dystopo.app.tracking.TerrainSource.json())
            "POST" to "/location/terrain" -> {
                val compass = (p.s("compass") ?: "course").lowercase()
                if (compass != "course" && compass != "sensor") return Response.json(mapOf("error" to "compass must be course (the replay's bearing) or sensor (the phone's)"), 400)
                val ts = io.github.jasondorjeshort.dystopo.app.tracking.TerrainSource
                val giveUp = p.d("giveUpM")
                if (giveUp != null && giveUp !in 30.0..300.0) return Response.json(mapOf("error" to "giveUpM must be 30 to 300 m (the app's limit is 300)"), 400)
                if (!ts.debugRun(compass == "course", giveUp)) return Response.json(mapOf("error" to "the terrain source is not watching (${ts.off})", "off" to ts.off), 409)
                if (r.body.isEmpty()) return Response.json(ts.json())
                return replay(t, r, p)
            }
        }
        waypointPath.matchEntire(r.path)?.let { return waypointRoute(t, r, p, it.groupValues[1], it.groupValues[2]) }
        val m = trackPath.matchEntire(r.path) ?: return if (r.path.startsWith("/tracks")) Response.notFound() else null
        val id = m.groupValues[1]
        val suffix = m.groupValues[2]
        if (id == "-") {
            if (r.method == "POST" && suffix == "/waypoint") return waypoint(t, null, p)
            return Response.notFound()
        }
        val summary = t.call { t.store.summary(id) } ?: return Response.json(mapOf("error" to "no track $id"), 404)
        return when (r.method to suffix) {
            "GET" to "" -> Response.json(
                trackJson(t, summary) + mapOf(
                    "waypoints" to t.call { t.store.waypoints(id) }.map(::waypointJson), "gaps" to t.call { t.store.gaps(id) }.map(::gapJson),
                    "bridged" to t.call { t.store.track(id) }?.let(::bridgedJson), // T260
                ),
            )
            "DELETE" to "" -> Response.json(mapOf("deleted" to t.delete(id)))
            "GET" to ".gpx" -> {
                val gpx = t.call { TrackExport.gpx(t.store, id) }!!
                Response(200, "application/gpx+xml; charset=utf-8", gpx.toByteArray())
            }
            "POST" to "/export" -> {
                val f = t.export(id)!!
                Response.json(mapOf("path" to f.path, "bytes" to f.length(), "name" to f.name))
            }
            "POST" to "/show" -> { // T262: on=0 hides it on this device's map
                val on = p.s("on") != "0"
                val ok = t.setShown(id, on)
                t.call { } // the overlay's redraw, posted before this, has run
                Response.json(mapOf("shown" to (ok && on), "found" to ok, "overlayFeatures" to t.overlay.features, "overlayTracks" to t.overlay.tracksDrawn))
            }
            "POST" to "/waypoint" -> waypoint(t, id, p)
            "GET" to "/profile" -> {
                val n = p.d("n")?.toInt() ?: Tracking.PROFILE_SAMPLES
                if (n !in 2..5000) return Response.json(mapOf("error" to "n must be 2 to 5000"), 400)
                val samples = t.call { t.profileOf(id, n) }.orEmpty()
                Response.json(
                    mapOf(
                        "id" to id, "n" to samples.size, "distanceM" to samples.lastOrNull()?.distanceM,
                        "minEleM" to samples.mapNotNull { it.eleM }.minOrNull(), "maxEleM" to samples.mapNotNull { it.eleM }.maxOrNull(),
                        "samples" to samples.map { listOf(it.distanceM, it.eleM, it.lat, it.lon) },
                    ),
                )
            }
            "POST" to "/profile" -> Response.json(mapOf("profile" to t.showProfile(id), "shown" to t.shownId))
            else -> Response.notFound()
        }
    }

    /** `POST /location/replay` (and T203's `POST /location/terrain` after its reset). */
    private fun replay(t: Tracking, r: Request, p: Params): Response {
        val rate = p.d("rate") ?: 1.0
        // T57: record= while a track is recording would leave that one behind (empty when it had just
        // started) beside the replay's own: refuse, and say how to get either. T65: the rules are
        // ReplayChecks.refusal, unit-tested.
        val recording = p.s("record") != null && t.call { t.recorder.recording }
        // T146: `route=current&kmh=` replays the current route timed in the app, no body.
        val route = p.s("route")
        val kmh = p.d("kmh")
        val plan = if (route == "current") io.github.jasondorjeshort.dystopo.app.routing.RouteSession.plan else null
        ReplayChecks.refusal(rate, r.body.isEmpty(), p.s("record"), recording, route, kmh, plan != null, TimedTrack.MAX_KMH)?.let { (status, error) ->
            return Response.json(if (status == 409) mapOf("error" to error, "recording" to recorderJson(t)) else mapOf("error" to error), status)
        }
        // T159: accuracy= may carry ranges (120@40-60) and stall= a silence; checked before anything starts.
        val accuracy = try {
            ReplayScript.defaultAccuracy(p.s("accuracy")) ?: 5.0
        } catch (e: IllegalArgumentException) {
            return Response.json(mapOf("error" to e.message), 400)
        }
        var scriptError: String? = null
        val script = { fixes: Int ->
            try {
                ReplayScript.parse(p.s("accuracy"), p.s("stall"), fixes)
            } catch (e: IllegalArgumentException) {
                scriptError = e.message
                throw ScriptRefused()
            }
        }
        return try {
            if (plan == null) io.github.jasondorjeshort.dystopo.gpx.Gpx.parse(r.bodyText).tracks.flatMap { it.points }.size.let(script)
            p.s("record")?.let { t.start(it, Tracking.Mode.INJECTED) }
            val started = if (plan != null && kmh != null) {
                val track = TimedTrack.track(plan.route, kmh, System.currentTimeMillis() / 1000 * 1000)
                t.replay.startTracks(listOf(track), rate, accuracy, "route", kmh, script)
            } else t.replay.start(r.bodyText, rate, accuracy, script)
            Response.json(started + mapOf("recording" to t.recorder.activeId))
        } catch (e: ScriptRefused) {
            Response.json(mapOf("error" to scriptError), 400)
        } catch (e: IllegalArgumentException) {
            // GpxException and XmlException are IllegalArgumentExceptions too.
            Response.json(mapOf("error" to "bad GPX: ${e.message}"), 400)
        }
    }

    private fun waypointRoute(t: Tracking, r: Request, p: Params, wid: String, suffix: String): Response {
        val w = t.call { t.store.waypoint(wid) } ?: return Response.json(mapOf("error" to "no waypoint $wid"), 404)
        return when (r.method to suffix) {
            "GET" to "" -> Response.json(storedJson(w))
            "POST" to "" -> {
                val name = p.s("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return Response.json(mapOf("error" to "pass name="), 400)
                t.renameWaypoint(wid, name)
                Response.json(storedJson(t.call { t.store.waypoint(wid) }!!))
            }
            "DELETE" to "" -> Response.json(mapOf("deleted" to t.deleteWaypoint(wid)))
            "POST" to "/select" -> {
                w.trackId?.let { if (it in t.call { t.hiddenTracks() }) t.show(it) } // T262: shown if it was hidden
                Response.json(mapOf("selected" to t.selectWaypoint(wid)) + storedJson(w))
            }
            else -> Response.notFound()
        }
    }

    private fun import(t: Tracking, r: Request, p: Params): Response {
        return try {
            val file = p.s("file")
            val res: TrackImport.Result = when {
                file != null -> t.importFile(file)
                r.body.isNotEmpty() && !r.bodyText.trimStart().startsWith("{") -> t.importGpx(r.bodyText, p.s("name") ?: "Imported track")
                else -> return Response.json(mapOf("error" to "POST a GPX body, or ?file=<name> from ${t.filesDir.path}"), 400)
            }
            Response.json(
                mapOf(
                    "imported" to res.imported.map(::summaryJson), "skippedTracks" to res.skippedTracks,
                    "waypoints" to res.waypoints, "skippedWaypoints" to res.skippedWaypoints,
                    "routesIgnored" to res.routesIgnored, "ignored" to res.ignored, "shown" to t.shownId,
                ),
            )
        } catch (e: IllegalArgumentException) {
            Response.json(mapOf("error" to "import failed: ${e.message}"), 400)
        }
    }

    /** T262: the screen's rows under [filter] (every row for ALL), as drawn; main thread. */
    private fun screenRows(filter: io.github.jasondorjeshort.dystopo.app.tracks.TracksUi.Filter): List<io.github.jasondorjeshort.dystopo.app.tracks.ListRow> {
        val ui = io.github.jasondorjeshort.dystopo.app.tracking.TrackingUiState.ui
        val trips = io.github.jasondorjeshort.dystopo.app.trips.Trips
        return io.github.jasondorjeshort.dystopo.app.tracks.listRows(
            ui.tracks, ui.distances, ui.activeId, ui.distanceM, ui.hidden, trips.list, trips.hiddenRoutes, filter, AppSettings.units,
            { ms -> io.github.jasondorjeshort.dystopo.app.trips.shortDate(ms) },
        ) { r -> io.github.jasondorjeshort.dystopo.app.routing.PaceSettings.figures(r.distanceM, r.ascentM, r.descentM, r.timeS, io.github.jasondorjeshort.dystopo.routing.TravelMode.of(r.mode)) }
    }

    /** T262: waits until the tracker's and the routes' state reached the main thread, so an answer reads what a change did. */
    private fun settle(t: Tracking) {
        t.refreshUi()
        io.github.jasondorjeshort.dystopo.app.trips.Trips.refresh()
        t.call { }
        DystopoApp.instance.map.onMain { }
    }

    /** T262: `POST /tracks/ui`: what a tap on the screen does (see the class comment). */
    private fun screenPost(app: DystopoApp, t: Tracking, p: Params): Response {
        val ui = io.github.jasondorjeshort.dystopo.app.tracks.TracksUi
        val filters = io.github.jasondorjeshort.dystopo.app.tracks.TracksUi.Filter.entries
        p.s("importHoldMs")?.toLongOrNull()?.let { io.github.jasondorjeshort.dystopo.app.routing.ImportText.holdMs = it.coerceIn(0, 30_000) }
        val filter = p.s("filter")?.let { code -> filters.firstOrNull { it.code == code } ?: return Response.json(mapOf("error" to "filter must be all, tracks or routes"), 400) }
        settle(t)
        app.map.onMain {
            val bar = io.github.jasondorjeshort.dystopo.app.tracking.TrackBar
            when (p.s("open")) {
                "1" -> { bar.open = true; ui.show(filter ?: io.github.jasondorjeshort.dystopo.app.tracks.TracksUi.Filter.TRACKS) }
                "0" -> ui.close()
                else -> filter?.let { ui.filter = it }
            }
            when (p.s("more")) { "1" -> ui.moreOpen = true; "0" -> ui.moreOpen = false }
            when (p.s("import")) { "1" -> ui.importOpen = true; "0" -> ui.importOpen = false }
            when (p.s("bar")) { "1" -> bar.open = true; "0" -> bar.hide() } // T253: unfold, or Hide
        }
        val all = app.map.onMain { screenRows(io.github.jasondorjeshort.dystopo.app.tracks.TracksUi.Filter.ALL) }
        fun row(ref: String) = all.firstOrNull { it.key == ref || it.id == ref }
        p.s("eye")?.let { ref ->
            val r = row(ref) ?: return Response.json(mapOf("error" to "no row $ref"), 404)
            if (r.kind == "route") io.github.jasondorjeshort.dystopo.app.trips.Trips.setShown(r.id, !r.shown) else t.setShown(r.id, !r.shown)
        }
        p.s("menu")?.let { ref ->
            if (ref == "none") { app.map.onMain { ui.menu = null; ui.renaming = null; ui.deleteAsk = null }; return@let }
            val r = row(ref) ?: return Response.json(mapOf("error" to "no row $ref"), 404)
            app.map.onMain { ui.menu = r.key; ui.renaming = null; ui.deleteAsk = null }
            val action = p.s("action") ?: return@let
            val label = MENU_ACTIONS[action] ?: return Response.json(mapOf("error" to "action must be one of ${MENU_ACTIONS.keys}"), 400)
            if (label !in io.github.jasondorjeshort.dystopo.app.tracks.menuOf(r.kind, r.recording)) return Response.json(mapOf("error" to "a ${r.kind} row has no $action"), 400)
            when {
                action == "rename" && p.s("name") != null -> {
                    val name = p.s("name")!!.trim()
                    if (name.isEmpty()) return Response.json(mapOf("error" to "name is blank"), 400)
                    app.map.onMain { ui.menu = null }
                    if (r.kind == "route") io.github.jasondorjeshort.dystopo.app.trips.Trips.rename(r.id, name) else t.rename(r.id, name)
                }
                action == "delete" && p.s("confirm") == "1" -> {
                    app.map.onMain { ui.menu = null }
                    if (r.kind == "route") io.github.jasondorjeshort.dystopo.app.trips.Trips.delete(r.id) else t.delete(r.id)
                }
                else -> app.map.onMain { io.github.jasondorjeshort.dystopo.app.tracks.menuAction(r, label) }
            }
        }
        return Response.json(screenJson(t))
    }

    /** T262: the `action=` words of `POST /tracks/ui` and the menu entries they tap. */
    private val MENU_ACTIONS = linkedMapOf(
        "statistics" to io.github.jasondorjeshort.dystopo.app.tracks.STATISTICS, "open" to io.github.jasondorjeshort.dystopo.app.tracks.OPEN,
        "rename" to io.github.jasondorjeshort.dystopo.app.tracks.RENAME, "export" to io.github.jasondorjeshort.dystopo.app.tracks.EXPORT_GPX,
        "go" to io.github.jasondorjeshort.dystopo.app.tracks.GO, "activity" to io.github.jasondorjeshort.dystopo.app.tracks.ACTIVITY,
        "report" to io.github.jasondorjeshort.dystopo.app.tracks.REPORT, "shareLink" to io.github.jasondorjeshort.dystopo.app.tracks.SHARE_LINK,
        "delete" to io.github.jasondorjeshort.dystopo.app.tracks.DELETE,
    )

    /** T262: `GET /tracks/ui`: the Tracks and routes screen as drawn. */
    private fun screenJson(t: Tracking): Map<String, Any?> {
        settle(t)
        return DystopoApp.instance.map.onMain {
            val tu = io.github.jasondorjeshort.dystopo.app.tracks.TracksUi
            val ui = t.ui
            val all = screenRows(io.github.jasondorjeshort.dystopo.app.tracks.TracksUi.Filter.ALL)
            val rows = screenRows(tu.filter)
            val menuRow = all.firstOrNull { it.key == tu.menu }
            val askRow = all.firstOrNull { it.key == tu.deleteAsk }
            linkedMapOf(
                "open" to tu.open, "title" to io.github.jasondorjeshort.dystopo.app.tracks.TITLE, "filter" to tu.filter.code,
                "filters" to io.github.jasondorjeshort.dystopo.app.tracks.TracksUi.Filter.entries.map { f ->
                    val n = all.count { f == io.github.jasondorjeshort.dystopo.app.tracks.TracksUi.Filter.ALL || (f == io.github.jasondorjeshort.dystopo.app.tracks.TracksUi.Filter.TRACKS) == (it.kind == "track") }
                    linkedMapOf("code" to f.code, "label" to io.github.jasondorjeshort.dystopo.app.tracks.filterLabel(f, n), "selected" to (f == tu.filter))
                },
                "moreOpen" to tu.moreOpen, "overflow" to listOf("Sync now", "Sync status", "Share"), "importOpen" to tu.importOpen,
                "message" to ui.message,
                "menu" to tu.menu, "menuEntries" to menuRow?.let { io.github.jasondorjeshort.dystopo.app.tracks.menuOf(it.kind, it.recording) },
                "renaming" to tu.renaming, "deleteAsk" to tu.deleteAsk,
                "deleteQuestion" to askRow?.let { io.github.jasondorjeshort.dystopo.app.tracks.deleteQuestion(it.kind, it.name) },
                "counts" to linkedMapOf(
                    "tracks" to all.count { it.kind == "track" }, "routes" to all.count { it.kind == "route" },
                    "shown" to all.count { it.shown }, "hidden" to all.count { !it.shown },
                ),
                "rows" to rows.map { r ->
                    linkedMapOf(
                        "kind" to r.kind, "id" to r.id, "name" to r.name, "line" to r.line, "activity" to r.activity, "shown" to r.shown,
                        "state" to io.github.jasondorjeshort.dystopo.app.tracks.shownWord(r.shown), "recording" to r.recording, "counts" to r.counts,
                    )
                },
                "saveFile" to io.github.jasondorjeshort.dystopo.app.SaveFile.json(),
                // T253: the unfolded bar and its record group (Hide first, the group last), and Discard's question.
                "bar" to linkedMapOf(
                    "open" to io.github.jasondorjeshort.dystopo.app.tracking.TrackBar.open,
                    "recordGroup" to io.github.jasondorjeshort.dystopo.app.tracking.recordGroup(ui.recording, ui.paused, io.github.jasondorjeshort.dystopo.app.tracking.TrackingUiState.starting),
                    "discardAsk" to io.github.jasondorjeshort.dystopo.app.tracking.TrackBar.discardAsk,
                ),
            )
        }
    }

    private fun waypoint(t: Tracking, trackId: String?, p: Params): Response {
        val w = t.call { t.recorder.waypoint(p.s("name"), trackId, p.d("lat"), p.d("lon"), p.d("ele")) }
            ?: return Response.json(mapOf("error" to "no position: pass lat and lon, or inject a fix first"), 409)
        t.refreshUi()
        t.refreshOverlay(force = true)
        return Response.json(waypointJson(w) + mapOf("track" to trackId))
    }

    private class ScriptRefused : RuntimeException()

    private fun recorderJson(t: Tracking): Map<String, Any?> = t.call {
        val rec = t.recorder
        val now = android.os.SystemClock.elapsedRealtime()
        val w = t.watch
        linkedMapOf(
            "id" to rec.activeId, "mode" to t.mode.name.lowercase(), "paused" to rec.paused, "service" to t.serviceState, "gps" to t.gpsState,
            "activity" to rec.profile.activity.code, "profile" to io.github.jasondorjeshort.dystopo.app.tracking.RecordingProfiles.json(rec.profile), // T172
            // T159: the one state, the line the controls show, and what drives it.
            "state" to t.stateNow().code, "stateLine" to t.ui.stateLine, "autoPaused" to rec.autoPaused, "pendingGap" to rec.pendingGap?.code,
            "serviceForeground" to t.serviceForeground, "serviceDown" to t.serviceDown, "serviceRestarts" to t.serviceRestarts, "tasksRemoved" to t.tasksRemoved,
            "stoppedBySystemAt" to t.stoppedBySystemAt,
            "silentS" to w.silentMs(now) / 1000.0, "lastAccuracyM" to w.lastPrimaryAccuracyM,
            "watchdog" to linkedMapOf(
                "count" to w.watchdogRestarts, "timeoutMs" to rec.profile.watchdogMs, "registrations" to t.gps.registrations,
                "lastAgoS" to if (w.watchdogRestarts == 0L) null else (now - w.lastWatchdogAt) / 1000.0,
            ),
            "fallback" to linkedMapOf(
                "enabled" to rec.profile.networkFallback, "provider" to t.fallback.name, "listening" to t.fallback.listening,
                "fixes" to w.fallbackFixes, "ignored" to t.fallbackIgnored, "lastAccuracyM" to w.lastFallbackAccuracyM,
                "afterS" to w.waitingAfterMs / 1000,
            ),
            "notification" to t.notification.json(),
            "acceptedFixes" to rec.accepted, "flagged" to rec.flagged, "rejectedSpeed" to rec.rejectedSpeed, "rejectedTime" to rec.rejectedTime, // T158
            "skippedPaused" to rec.skippedPaused, "skippedAutoPaused" to rec.skippedAutoPaused, "gapSplits" to rec.gapSplits, "gapsWritten" to rec.gapsWritten,
            "gpsIgnored" to t.gpsIgnored,
            // T251: the counts as the card's waiting line says them, and the puck against the track's head.
            "counts" to rec.counts().let { c ->
                linkedMapOf(
                    "accepted" to c.accepted, "flagged" to c.flagged, "rejectedSpeed" to c.rejectedSpeed, "rejectedTime" to c.rejectedTime,
                    "gapSplits" to c.gapSplits, "dropped" to c.dropped, "lastFlaggedAccuracyM" to c.lastFlaggedAccuracyM,
                    "line" to io.github.jasondorjeshort.dystopo.app.tracking.RecordingStatus.countsLine(c),
                )
            },
            "head" to rec.lastKept?.let { linkedMapOf("lat" to it.lat, "lon" to it.lon, "accuracyM" to it.accuracyM, "flags" to it.flags, "time" to it.time) },
            "puck" to runCatching {
                val pk = io.github.jasondorjeshort.dystopo.app.DystopoApp.instance.map.puckJson()
                val head = rec.lastKept
                val la = pk["lat"] as? Double
                val lo = pk["lon"] as? Double
                pk + mapOf("toHeadM" to if (head != null && la != null && lo != null) Math.round(TrackStats.distanceM(la, lo, head.lat, head.lon) * 10) / 10.0 else null)
            }.getOrNull(),
            "gaps" to rec.activeId?.let { id -> t.store.gaps(id).map(::gapJson) },
            "bridged" to rec.activeId?.let { bridgedJson(io.github.jasondorjeshort.dystopo.model.Track(it, "", rec.livePoints().map { s -> io.github.jasondorjeshort.dystopo.model.TrackSegment(s) }, profile = rec.profile)) }, // T260
            "liveStats" to rec.liveStats()?.let(::statsJson),
        )
    }

    /** T260: the gaps [track]'s line bridges on the map. */
    private fun bridgedJson(track: io.github.jasondorjeshort.dystopo.model.Track): Map<String, Any?> {
        val gaps = io.github.jasondorjeshort.dystopo.gpx.TrackDrawing.gaps(track)
        return linkedMapOf(
            "count" to gaps.size, "totalS" to gaps.sumOf { it.durationMs } / 1000.0,
            "text" to io.github.jasondorjeshort.dystopo.gpx.TrackDrawing.gapText(gaps),
            "intervalMs" to io.github.jasondorjeshort.dystopo.gpx.TrackDrawing.intervalOf(track),
            "list" to gaps.map { g ->
                linkedMapOf(
                    "from" to listOf(g.from.lat, g.from.lon), "to" to listOf(g.to.lat, g.to.lon), "startTime" to g.from.time, "endTime" to g.to.time,
                    "durationS" to g.durationMs / 1000.0, "straightM" to Math.round(g.straightM * 10) / 10.0,
                )
            },
        )
    }

    private fun gapJson(g: io.github.jasondorjeshort.dystopo.model.TrackGap): Map<String, Any?> =
        linkedMapOf("start" to g.start, "end" to g.end, "durationS" to g.durationMs / 1000.0, "reason" to g.reason.code)

    private fun settingsJson(t: Tracking): Map<String, Any?> = mapOf("gapS" to t.gapSeconds)

    private fun overview(t: Tracking): Map<String, Any?> {
        val recording = if (t.recorder.recording) recorderJson(t) else null
        val wayBack = runCatching { io.github.jasondorjeshort.dystopo.app.nav.WayBackClock.json() }.getOrElse { mapOf("error" to it.message) } // T180
        return t.call {
            val rec = t.recorder
            val tracks = t.store.list()
            val hiddenTracks = t.store.hidden(io.github.jasondorjeshort.dystopo.store.ShownKind.TRACK) // T262
            val hiddenRoutes = t.store.hidden(io.github.jasondorjeshort.dystopo.store.ShownKind.ROUTE)
            val routeIds = (t.store as? io.github.jasondorjeshort.dystopo.sync.SyncStore)?.routeList()?.map { it.id }.orEmpty()
            val ui = t.ui
            mapOf(
                "state" to t.state.code, // T159
                "recording" to recording,
                "wayBack" to wayBack, // T180
                "freeWaypoints" to t.store.waypoints(null).map(::waypointJson), // T91
                "lastDrop" to t.lastDrop,
                "lastFix" to rec.lastFix?.let {
                    mapOf(
                        "lat" to it.lat, "lon" to it.lon, "ele" to it.ele, "time" to it.time, "source" to it.source,
                        "accuracyM" to it.accuracyM, "speedMps" to it.speedMps, "courseDeg" to it.courseDeg, "verticalAccuracyM" to it.verticalAccuracyM, // T159
                    )
                },
                // T262: the shown set (per device, never synced), and the one track a reader of one track draws.
                "shown" to mapOf("tracks" to tracks.map { it.id }.filter { it !in hiddenTracks }, "routes" to routeIds.filter { it !in hiddenRoutes }),
                "hidden" to mapOf("tracks" to tracks.map { it.id }.filter { it in hiddenTracks }, "routes" to routeIds.filter { it in hiddenRoutes }),
                "shownId" to t.shownId,
                "selectedWaypoint" to t.selectedWaypointId,
                "profile" to t.profileId,
                "ui" to mapOf(
                    "paused" to ui.paused, "sheetOpen" to (ui.selectedWaypoint != null), "profileSamples" to ui.profile?.samples?.size,
                    "gapText" to ui.gapText, "profileGapText" to ui.profile?.gapText, // T260
                ),
                "overlay" to mapOf(
                    "features" to t.overlay.features, "labels" to t.overlay.labels, "installed" to t.overlay.installedOnStyle,
                    "bridges" to t.overlay.bridges, "bridgedS" to t.overlay.bridgedMs / 1000.0, // T260
                    "tracks" to t.overlay.tracksDrawn, "routes" to t.overlay.routesDrawn, "live" to t.overlay.liveDrawn, "storedSends" to t.overlay.storedSends, // T262
                    "parts" to t.overlay.parts, "seamCuts" to t.overlay.seamCuts, "lastFit" to t.overlay.lastFit, // T265
                ),
                "replay" to t.replay.json(),
                "settings" to settingsJson(t),
                "interruptedAtStartup" to t.interrupted,
                "store" to mapOf(
                    "type" to "sqldelight", "file" to t.dbFile.path, "bytes" to t.dbFile.length(), "tracks" to tracks.size,
                    "schemaVersion" to io.github.jasondorjeshort.dystopo.store.db.TrackDb.Schema.version,
                ),
                "tracks" to tracks.map { s -> summaryJson(s) + mapOf("stats" to t.statsOf(s)?.let(::statsJson), "gaps" to t.store.gaps(s.id).size, "shown" to (s.id !in hiddenTracks)) },
                "statsComputed" to t.store.statsComputed,
            )
        }
    }

    private fun trackJson(t: Tracking, s: TrackSummary): Map<String, Any?> =
        summaryJson(s) + mapOf("stats" to t.call { t.statsOf(s) }?.let(::statsJson))

    private fun summaryJson(s: TrackSummary): Map<String, Any?> = mapOf(
        "id" to s.id, "name" to s.name, "createdAt" to s.createdAt, "updatedAt" to s.updatedAt,
        "recording" to s.recording, "points" to s.points, "waypoints" to s.waypoints,
        "activity" to s.activity?.code, // T172
    )

    private fun waypointJson(w: Waypoint): Map<String, Any?> =
        mapOf("id" to w.id, "name" to w.name, "lat" to w.lat, "lon" to w.lon, "ele" to w.ele, "time" to w.time)

    private fun storedJson(w: StoredWaypoint): Map<String, Any?> = waypointJson(w.waypoint) + mapOf("track" to w.trackId)

    private fun statsJson(s: TrackStats): Map<String, Any?> = mapOf(
        "segments" to s.segments, "points" to s.points, "distanceM" to s.distanceM,
        "ascentM" to s.ascentM, "descentM" to s.descentM, "minEleM" to s.minEleM, "maxEleM" to s.maxEleM,
        "startTime" to s.startTime, "endTime" to s.endTime, "durationS" to s.durationS, "movingTimeS" to s.movingTimeS,
        "movingSpeedMps" to s.movingSpeedMps, "movingPaceSPerKm" to s.movingPaceSPerKm,
        "bounds" to s.bounds?.let { listOf(it.west, it.south, it.east, it.north) },
    )

    private class Params(val map: Map<String, Any?>) {
        fun d(key: String): Double? = when (val v = map[key]) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }?.takeIf { it.isFinite() }

        fun s(key: String): String? = map[key]?.toString()?.takeIf { it.isNotEmpty() }
    }

    /** Query parameters, overlaid by a JSON object body when there is one (a GPX body is left alone). */
    private fun params(r: Request): Params {
        val m = HashMap<String, Any?>(r.query)
        val body = r.bodyText.trim()
        if (body.startsWith("{")) runCatching { m.putAll(MiniJson.parseObject(body)) }
        return Params(m)
    }
}
